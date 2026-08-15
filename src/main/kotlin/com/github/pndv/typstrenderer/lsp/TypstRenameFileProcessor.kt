package com.github.pndv.typstrenderer.lsp

import com.github.pndv.typstrenderer.TypstBundle
import com.github.pndv.typstrenderer.language.TypstFileType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.platform.lsp.api.LspClient
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.rename.RenamePsiFileProcessor
import com.intellij.usageView.UsageInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.FileRename
import org.eclipse.lsp4j.RenameFilesParams
import org.eclipse.lsp4j.WorkspaceEdit
import java.nio.file.Path

private val LOG = logger<TypstRenameFileProcessor>()

/** How long to wait for tinymist to work out which imports a rename affects. */
private const val WILL_RENAME_TIMEOUT_MS = 10_000

/**
 * The edits tinymist computed for a pending rename, parked on the file between the two hooks
 * below. Keyed per file so a second rename can never pick up a previous answer.
 */
internal val PENDING_IMPORT_EDITS = Key.create<WorkspaceEdit>("typst.rename.pendingImportEdits")

/**
 * Keeps `#import` and `#include` paths correct when a `.typ` file is renamed from the Project view.
 *
 * The platform renames the file itself, but this plugin deliberately gives Typst no PSI references
 * — the parser is lexer-only and all language intelligence lives in tinymist — so IntelliJ has
 * nothing to rewrite, and every path naming the old file would be left dangling.
 *
 * tinymist answers `workspace/willRenameFiles` with the finished edits. The request has to go out
 * *before* the file moves: the server resolves the file by its old path (`file_id_by_path(&left)`
 * in `will_rename_files.rs`), and once the rename has happened that path no longer exists, so the
 * server would return nothing.
 *
 * Hence the split across two hooks:
 * - [prepareRenaming] runs on the EDT *outside* the refactoring's write action — the only place a
 *   blocking LSP round trip can show cancellable modal progress without freezing the IDE under a
 *   write lock. Its answer is parked on the file's user data.
 * - [renameElement] runs *inside* the write action and applies the parked edits before delegating
 *   to the platform, so the import rewrites and the rename land in one undoable command.
 */
class TypstRenameFileProcessor : RenamePsiFileProcessor() {

    override fun canProcessElement(element: PsiElement): Boolean =
        element is PsiFile && element.virtualFile?.fileType == TypstFileType

    override fun prepareRenaming(
        element: PsiElement,
        newName: String,
        allRenames: MutableMap<PsiElement, String>,
    ) {
        super.prepareRenaming(element, newName, allRenames)

        val file =
            (element as? PsiFile)?.virtualFile
            ?: return // Clear first: a rename that is later cancelled, or one whose request fails, must never // leave an answer behind for the next rename of the same file to apply.
        file.putUserData(PENDING_IMPORT_EDITS, null)

        val edits = computeImportEdits(element.project, file, newName) ?: return
        LOG.info("tinymist returned import updates for renaming ${file.name} to $newName")
        file.putUserData(PENDING_IMPORT_EDITS, edits)
    }

    override fun renameElement(
        element: PsiElement,
        newName: String,
        usages: Array<out UsageInfo>,
        listener: RefactoringElementListener?,
    ) {
        val file = (element as? PsiFile)?.virtualFile
        val pending = file?.getUserData(PENDING_IMPORT_EDITS)
        file?.putUserData(PENDING_IMPORT_EDITS, null)

        if (pending != null) { // Applied before the platform moves the file, so the ranges still describe the text
            // tinymist measured. Nesting a write command inside the refactoring's own command
            // merges into it, which keeps the whole rename process in a single undo step.
            TypstLspRenameHandler().applyWorkspaceEdit(element.project, textEditsOnly(pending))
        }

        super.renameElement(element, newName, usages, listener)
    }

    /**
     * Asks tinymist which imports a rename of [file] to [newName] would invalidate.
     *
     * Returns `null` — leaving the rename to proceed without import updates — whenever the answer
     * cannot be obtained: no client serves the file, the file has no real path, the user cancelled
     * the progress, or the request failed. Renaming without fixing imports is the pre-existing
     * behaviour and is recoverable by hand; blocking the rename outright would not be.
     */
    private fun computeImportEdits(project: Project, file: VirtualFile, newName: String): WorkspaceEdit? {
        val oldPath = file.toNioPathOrNull() ?: run {
            LOG.debug("Skipping import update: ${file.url} has no local path")
            return null
        }
        val newPath = oldPath.resolveSibling(newName)

        return try {
            runWithModalProgressBlocking(project, TypstBundle.message("rename.progress.updatingImports")) {
                withContext(Dispatchers.IO) { // Routed by file rather than by "whichever client answers first": a project can
                    // also be running folder-rooted clients for .typ files outside its content
                    // roots, and one of those cannot resolve this path.
                    val client = TinymistCommands.getClient(project, oldPath)
                    if (client == null) {
                        LOG.debug("Skipping import update: no tinymist client serves $oldPath")
                        null
                    } else {
                        requestImportEdits(client, oldPath, newPath)
                    }
                }
            }
        } catch (_: CancellationException) { // Cancelling the progress means "just rename the file"; it is a top-level user action
            // with no enclosing coroutine to propagate to. Caught before the generic branch because
            // ProcessCanceledException is a CancellationException and would otherwise be logged as
            // a failure.
            LOG.debug("Import update cancelled while renaming ${file.name}")
            null
        } catch (e: Exception) {
            LOG.warn("willRenameFiles failed for ${file.name}; imports will not be updated", e)
            null
        }
    }
}

/**
 * The `workspace/willRenameFiles` round trip itself, separated from progress and client lookup so
 * it can be exercised against a test double.
 */
internal fun requestImportEdits(client: LspClient, oldPath: Path, newPath: Path): WorkspaceEdit? {
    val params = RenameFilesParams(
        listOf(FileRename(oldPath.toUri().toString(), newPath.toUri().toString()))
    )
    return client.sendRequestSync(WILL_RENAME_TIMEOUT_MS) { ls ->
        ls.workspaceService.willRenameFiles(params)
    }
}

/**
 * Strips resource operations from [edit].
 *
 * `willRenameFiles` is defined to answer with text edits only — the client is the one performing
 * the rename — so this is a guard rather than a transformation. Were a server ever to include a
 * rename operation, applying it here would move the file out from under the platform, which is
 * about to rename it itself.
 */
internal fun textEditsOnly(edit: WorkspaceEdit): WorkspaceEdit {
    val filtered = WorkspaceEdit()
    filtered.changes = edit.changes
    edit.documentChanges?.filter { it.isLeft }?.let { textOnly ->
        val dropped = edit.documentChanges.size - textOnly.size
        if (dropped > 0) LOG.warn("Ignoring $dropped resource operation(s) in a willRenameFiles answer")
        filtered.documentChanges = textOnly
    }
    return filtered
}

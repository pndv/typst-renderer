package com.github.pndv.typstrenderer.lsp

import com.github.pndv.typstrenderer.TypstBundle
import com.github.pndv.typstrenderer.language.TypstFileType
import com.intellij.ide.TitledHandler
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.platform.lsp.api.LspClient
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.rename.RenameHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either3
import java.net.URI
import java.nio.file.Paths

private val LOG = logger<TypstLspRenameHandler>()

/**
 * Rename handler for Typst files that delegates to the tinymist LSP server.
 *
 * IntelliJ's built-in LSP module does not support `textDocument/rename`,
 * so this handler manually sends prepareRename and rename requests to the server.
 */
class TypstLspRenameHandler : RenameHandler, TitledHandler {

    /**
     * Shown in the platform's refactoring chooser when more than one rename handler applies.
     * Without this, `RenameHandlerRegistry.getHandlerTitle` falls back to `toString()` and the
     * user is offered a raw class name.
     */
    override fun getActionTitle(): String = TypstBundle.message("action.Typst.rename.symbol.text")

    override fun isAvailableOnDataContext(dataContext: DataContext): Boolean {
        val project =
            CommonDataKeys.PROJECT.getData(dataContext)
            ?: return false // Deliberately the cheap check, not the file-routed one: this is polled on every rename // action update, on a thread that cannot afford getClient's refreshing VFS lookup. Whether
        // the *right* client serves this file is settled in invoke(), off the EDT.
        return typstFileForSymbolRename(dataContext) != null && TinymistCommands.hasAnyClient(project)
    }

    /**
     * The Typst file whose symbols this handler can rename, or `null` when [dataContext] is not a
     * symbol-rename context at all.
     *
     * Symbol rename is caret-driven, so it only means anything when there is an editor. Without
     * one the user is renaming the *file* — from the Project view, the navigation bar, Recent
     * Files — and that belongs to the platform's own `PsiElementRenameHandler`.
     *
     * Claiming those contexts made file rename a silent no-op: `RenameHandlerRegistry` only falls
     * back to its default handler when *no* registered handler reports availability, so this
     * handler won the context outright, and the platform then dispatched to the element-based
     * [invoke] overload below, which cannot rename a file.
     *
     * The file comes from the editor's own document rather than from
     * [CommonDataKeys.VIRTUAL_FILE], so a Typst file selected elsewhere in the IDE cannot make an
     * unrelated editor look renameable. That also matches what [invoke] operates on.
     */
    internal fun typstFileForSymbolRename(dataContext: DataContext): VirtualFile? {
        val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return null
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
        return file.takeIf { it.fileType == TypstFileType }
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?, dataContext: DataContext?) {
        if (editor == null || file == null) return
        val virtualFile = file.virtualFile ?: return
        if (virtualFile.fileType != TypstFileType) return

        val client = resolveClientFor(project, virtualFile) ?: run {
            Messages.showWarningDialog(project, "Tinymist LSP server is not running.", "Rename")
            return
        }

        performRenameWithClient(project, editor, virtualFile, client)
    }

    /**
     * Finds the tinymist client that actually claims [virtualFile].
     *
     * Routed by file rather than taking whichever client is first: a project runs one client for
     * its own content plus a folder-rooted client for every folder holding an out-of-project `.typ`
     * file, and a client rooted elsewhere cannot resolve this path — it answers nothing, and the
     * rename appears to refuse for no reason. [TinymistCommands.getClient] also rejects a client
     * that has not finished initialising, which produces the same symptom.
     *
     * Run off the EDT because that lookup refreshes the VFS. Normally it resolves from cache and
     * the progress never becomes visible.
     */
    private fun resolveClientFor(project: Project, virtualFile: VirtualFile): LspClient? {
        val path = virtualFile.toNioPathOrNull() ?: return null
        return try {
            runWithModalProgressBlocking(project, TypstBundle.message("rename.progress.locatingClient")) {
                withContext(Dispatchers.IO) { TinymistCommands.getClient(project, path) }
            }
        } catch (_: CancellationException) {
            LOG.debug("Rename cancelled while locating the tinymist client")
            null
        } catch (e: Exception) {
            LOG.warn("Could not locate a tinymist client for ${virtualFile.name}", e)
            null
        }
    }

    internal fun performRenameWithClient(
        project: Project,
        editor: Editor,
        virtualFile: VirtualFile,
        server: LspClient,
    ) {
        val offset = editor.caretModel.offset
        val position = offsetToLspPosition(editor.document, offset)
        val textDocId = TextDocumentIdentifier(virtualFile.url.toLspUri())

        // Step 1: prepareRename — validate that rename is possible here and get current name.
        // The LSP round-trip runs off the EDT on Dispatchers.IO under a cancellable modal
        // progress, so a slow or busy tinymist can't freeze the UI. runWithModalProgressBlocking
        // is the modern replacement for ProgressManager.runProcessWithProgressSynchronously.
        val prepareParams = PrepareRenameParams(textDocId, position)
        val prepareResult = try {
            runWithModalProgressBlocking(project, "Preparing rename…") {
                withContext(Dispatchers.IO) {
                    server.sendRequestSync(5000) { ls ->
                        ls.textDocumentService.prepareRename(prepareParams)
                    }
                }
            }
        } catch (_: CancellationException) { // The user cancelled the modal progress. Swallowing (rather than rethrowing) the
            // CancellationException is correct here because this is a top-level, user-initiated
            // EDT action — there is no enclosing coroutine or platform operation whose
            // structured cancellation we would be breaking by not propagating it. Cancelling
            // simply means "abort this rename", so we return quietly. It must be caught *before*
            // the generic Exception branch: ProcessCanceledException is a CancellationException
            // subtype, so without this branch a cancel would fall through and be misreported.
            LOG.debug("Rename cancelled while preparing")
            return
        } catch (e: Exception) {
            LOG.warn("prepareRename failed or not supported: ${e.message}")
            null
        }

        if (prepareResult == null) {
            Messages.showInfoMessage(project, "The symbol at the cursor cannot be renamed.", "Rename")
            return
        }

        // Extract the current name from the prepare result
        val currentName = extractCurrentName(prepareResult, editor.document) ?: getWordAtCaret(editor)

        // Step 2: Ask user for the new name
        val newName = Messages.showInputDialog(
            project,
            "Rename '$currentName' to:",
            "Rename Symbol",
            null,
            currentName,
            null
        )

        if (newName.isNullOrBlank() || newName == currentName) return

        // Step 3: Send rename request — same off-EDT cancellable modal treatment as prepareRename.
        val renameParams = RenameParams(textDocId, position, newName)
        val workspaceEdit = try {
            runWithModalProgressBlocking(project, "Renaming…") {
                withContext(Dispatchers.IO) {
                    server.sendRequestSync(10000) { ls ->
                        ls.textDocumentService.rename(renameParams)
                    }
                }
            }
        } catch (_: CancellationException) { // User cancelled the modal progress mid-rename — abort quietly. The same rationale as the
            // prepareRename branch above: no enclosing coroutine/operation to propagate to, and
            // catching it before the generic Exception branch keeps a cancel (a CancellationException,
            // which ProcessCanceledException extends) from being surfaced as a "Rename failed" error.
            LOG.debug("Rename cancelled while applying edits")
            return
        } catch (e: Exception) {
            LOG.warn("Rename request failed", e)
            Messages.showErrorDialog(project, "Rename failed: ${e.message}", "Rename Error")
            return
        }

        if (workspaceEdit == null) {
            Messages.showInfoMessage(project, "The server returned no edits.", "Rename")
            return
        }

        // Step 4: Apply workspace edits
        applyWorkspaceEdit(project, workspaceEdit)
    }

    override fun invoke(
        project: Project,
        elements: Array<out PsiElement>,
        dataContext: DataContext?
    ) { // The platform routes here when there is no editor — i.e. a file rename. isAvailableOnDataContext
        // declines those contexts so the platform's PsiElementRenameHandler handles them, which means
        // this overload should be unreachable. If it ever runs, the availability gate has drifted and
        // rename would silently do nothing, so leave a trace rather than failing mute.
        LOG.warn("Element-based rename invoked unexpectedly for ${elements.size} element(s); file rename should be handled by the platform")
    }

    // ---- Internal helpers ----

    /**
     * Converts an editor offset to an LSP Position (0-based line, 0-based character).
     */
    internal fun offsetToLspPosition(document: Document, offset: Int): Position {
        val line = document.getLineNumber(offset)
        val lineStartOffset = document.getLineStartOffset(line)
        val character = offset - lineStartOffset
        return Position(line, character)
    }

    /**
     * Converts an IntelliJ VirtualFile URL to an LSP-compatible file URI.
     * IntelliJ uses `file:///D:/path` format; LSP expects the same.
     */
    private fun String.toLspUri(): String {
        // IntelliJ VirtualFile.url is like "file:///D:/Projects/..." which is valid LSP URI
        return if (startsWith("file://")) this
        else VirtualFileManager.constructUrl("file", this)
    }

    /**
     * Extracts the current symbol name from the prepareRename result.
     * The result can be a Range, PrepareRenameResult (with placeholder), or PrepareRenameDefaultBehavior.
     */
    internal fun extractCurrentName(result: Any, document: Document): String? {
        return when (result) { // LSP4J types the prepareRename response as
            // Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>, so the payload has
            // to be unwrapped before it can be matched. Without this every `prepareResult` fell
            // through to the getWordAtCaret fallback, which silently disagrees with the server
            // whenever the name is not a bare identifier — tinymist answers "A.typ" for an import
            // path, while the word scan stops at the dot and yields "A". The third arm,
            // PrepareRenameDefaultBehavior, carries no name and correctly leaves it to the caller.
            is Either3<*, *, *> -> when {
                result.isFirst -> result.first?.let { extractCurrentName(it, document) }
                result.isSecond -> result.second?.let { extractCurrentName(it, document) }
                else -> null
            }
            is PrepareRenameResult -> result.placeholder
            is Range -> {
                val start = document.getLineStartOffset(result.start.line) + result.start.character
                val end = document.getLineStartOffset(result.end.line) + result.end.character
                document.getText(com.intellij.openapi.util.TextRange(start, end))
            }
            else -> null
        }
    }

    /**
     * Fallback: get the word under the cursor.
     */
    internal fun getWordAtCaret(editor: Editor): String {
        val offset = editor.caretModel.offset
        val document = editor.document
        val text = document.charsSequence

        var start = offset
        while (start > 0 && text[start - 1].isLetterOrDigit() || (start > 0 && text[start - 1] == '_')) start--
        var end = offset
        while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_')) end++

        return if (start < end) text.subSequence(start, end).toString() else "symbol"
    }

    /**
     * Applies an LSP WorkspaceEdit to the project's documents.
     */
    internal fun applyWorkspaceEdit(project: Project, edit: WorkspaceEdit) {
        WriteCommandAction.runWriteCommandAction(project, "Rename Symbol", null, {
            // Handle the `changes` field (Map<String, List<TextEdit>>)
            edit.changes?.forEach { (uri, textEdits) ->
                applyTextEdits(uri, textEdits)
            }

            // Handle the `documentChanges` field (List<Either<TextDocumentEdit, ResourceOperation>>).
            // Order matters and must be preserved: when the caret is on an import path, tinymist
            // emits the text edits first and the file rename last, so every edit still addresses
            // the old path at the moment it runs. Sorting or batching these would break them.
            edit.documentChanges?.forEach { change ->
                if (change.isLeft) {
                    val docEdit = change.left
                    applyTextEdits(docEdit.textDocument.uri, docEdit.edits)
                } else {
                    applyResourceOperation(change.right)
                }
            }
        })
    }

    /**
     * Applies an LSP resource operation.
     *
     * tinymist emits one when the caret sits on an import or include path: renaming `"A.typ"`
     * there rewrites every path that refers to the file *and* renames the file itself. Dropping
     * the rename half would leave the rewritten imports pointing at a file that no longer exists,
     * which breaks the build — a partial write is worse than no write at all.
     */
    internal fun applyResourceOperation(operation: ResourceOperation) {
        if (operation !is RenameFile) {
            LOG.warn("Ignoring unsupported LSP resource operation of kind '${operation.kind}'")
            return
        }

        val source = findVirtualFile(operation.oldUri) ?: run {
            LOG.warn("File rename skipped: ${operation.oldUri} is not in the VFS")
            return
        }
        val targetPath = try {
            Paths.get(URI(operation.newUri))
        } catch (e: Exception) {
            LOG.warn("File rename skipped: cannot parse target URI ${operation.newUri}", e)
            return
        }
        val newName = targetPath.fileName?.toString() ?: run {
            LOG.warn("File rename skipped: ${operation.newUri} has no file name")
            return
        }

        // A bare new name keeps the file where it is, but the user can type a path — renaming
        // "A.typ" to "sub/Z.typ" moves it as well. Resolve the target directory through the VFS
        // and bail out if it does not exist, rather than silently renaming into the wrong place
        // or conjuring directories the user never asked for.
        targetPath.parent?.let { parentPath ->
            val newParent = LocalFileSystem.getInstance().findFileByNioFile(parentPath) ?: run {
                LOG.warn("File rename skipped: target directory $parentPath is not in the VFS")
                return
            }
            if (newParent != source.parent) {
                LOG.info("Moving ${source.name} to ${newParent.path}")
                source.move(this, newParent)
            }
        }

        if (source.name != newName) {
            LOG.info("Renaming ${source.name} to $newName")
            source.rename(this, newName)
        }
    }

    /**
     * Applies a list of LSP TextEdits to the document identified by the URI.
     * Edits are applied in reverse order (bottom-to-top) so offsets remain valid.
     */
    internal fun applyTextEdits(uri: String, edits: List<TextEdit>) {
        val virtualFile = findVirtualFile(uri) ?: run {
            LOG.warn("Could not find file for URI: $uri")
            return
        }
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: run {
            LOG.warn("Could not get document for: $uri")
            return
        }

        // Sort edits in reverse document order so earlier edits don't shift later offsets
        val sortedEdits = edits.sortedWith(compareByDescending<TextEdit> { it.range.start.line }
            .thenByDescending { it.range.start.character })

        for (textEdit in sortedEdits) {
            val startOffset = document.getLineStartOffset(textEdit.range.start.line) + textEdit.range.start.character
            val endOffset = document.getLineStartOffset(textEdit.range.end.line) + textEdit.range.end.character
            document.replaceString(startOffset, endOffset, textEdit.newText)
        }
    }

    /**
     * Resolves an LSP URI to an IntelliJ VirtualFile.
     */
    internal fun findVirtualFile(uri: String): VirtualFile? {
        return try {
            VirtualFileManager.getInstance().findFileByNioPath(Paths.get(URI(uri)))
        } catch (e: Exception) {
            LOG.warn("Failed to resolve URI: $uri", e)
            null
        }
    }
}

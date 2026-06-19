package com.github.pndv.typstrenderer.lsp

import com.github.pndv.typstrenderer.language.TypstFileType
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.rename.RenameHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.*
import java.net.URI
import java.nio.file.Paths

private val LOG = logger<TypstLspRenameHandler>()

/**
 * Rename handler for Typst files that delegates to the tinymist LSP server.
 *
 * IntelliJ's built-in LSP module does not support `textDocument/rename`,
 * so this handler manually sends prepareRename and rename requests to the server.
 */
class TypstLspRenameHandler : RenameHandler {

    override fun isAvailableOnDataContext(dataContext: DataContext): Boolean {
        val file = CommonDataKeys.VIRTUAL_FILE.getData(dataContext) ?: return false
        val project = CommonDataKeys.PROJECT.getData(dataContext) ?: return false
        return file.fileType == TypstFileType && findLspServer(project) != null
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?, dataContext: DataContext?) {
        if (editor == null || file == null) return
        val virtualFile = file.virtualFile ?: return
        if (virtualFile.fileType != TypstFileType) return

        val server = findLspServer(project) ?: run {
            Messages.showWarningDialog(project, "Tinymist LSP server is not running.", "Rename")
            return
        }

        performRenameWithServer(project, editor, virtualFile, server)
    }

    internal fun performRenameWithServer(
        project: Project,
        editor: Editor,
        virtualFile: VirtualFile,
        server: LspServer,
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

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) {
        // Not used for LSP-based rename — the editor-based invoke() handles everything
    }

    // ---- Internal helpers ----

    private fun findLspServer(project: Project): LspServer? {
        val manager = LspServerManager.getInstance(project)
        val servers = manager.getServersForProvider(TinymistLspServerSupportProvider::class.java)
        return servers.firstOrNull()
    }

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
        return when (result) {
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

            // Handle the `documentChanges` field (List<Either<TextDocumentEdit, ResourceOperation>>)
            edit.documentChanges?.forEach { change ->
                if (change.isLeft) {
                    val docEdit = change.left
                    applyTextEdits(docEdit.textDocument.uri, docEdit.edits)
                }
                // Resource operations (create/rename/delete file) not yet supported
            }
        })
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

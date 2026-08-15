package com.github.pndv.typstrenderer.lsp

import com.github.pndv.typstrenderer.pluginRegisteredInTestPlatform
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientDescriptor
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.nio.file.Files
import java.util.concurrent.CompletableFuture

/**
 * Tests for [TypstRenameFileProcessor], which keeps `#import` paths correct when a `.typ` file is
 * renamed from the Project view.
 *
 * The `prepareRenaming` half cannot be exercised end to end here: it needs a live tinymist client,
 * and none starts in a fixture because the platform gates `fileOpened` on
 * `ProjectFileIndex.isInContent` while fixture files live in the out-of-content `temp://` VFS. The
 * LSP round trip is therefore tested through [requestImportEdits] against a test double, and the
 * apply half through [TypstRenameFileProcessor.renameElement] with the edits parked directly.
 */
class TypstRenameFileProcessorTest : BasePlatformTestCase() {

    private val processor = TypstRenameFileProcessor()

    // ---- canProcessElement ----

    fun testCanProcessElement_typstFile_isTrue() {
        if (!pluginRegisteredInTestPlatform()) return
        val psiFile = myFixture.configureByText("A.typ", "#let greeting = 1")
        assertTrue(processor.canProcessElement(psiFile))
    }

    fun testCanProcessElement_nonTypstFile_isFalse() {
        val psiFile = myFixture.configureByText("notes.txt", "plain text")
        assertFalse(processor.canProcessElement(psiFile))
    }

    fun testCanProcessElement_nonFileElement_isFalse() {
        if (!pluginRegisteredInTestPlatform()) return
        val psiFile = myFixture.configureByText("A.typ", "#let greeting = 1")
        val leaf = checkNotNull(psiFile.firstChild) { "expected a child element" }
        assertFalse(processor.canProcessElement(leaf))
    }

    // ---- requestImportEdits ----

    fun testRequestImportEdits_returnsServerAnswer() {
        val expected = WorkspaceEdit(mapOf("file:///B.typ" to listOf<TextEdit>()))
        val client = FakeWillRenameClient(expected)

        val actual = requestImportEdits(
            client,
            java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "A.typ"),
            java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "Z.typ"),
        )

        assertSame(expected, actual)
        assertEquals(1, client.callCount)
    }

    fun testRequestImportEdits_serverReturnsNothing_returnsNull() {
        val client = FakeWillRenameClient(null)

        val actual = requestImportEdits(
            client,
            java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "A.typ"),
            java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "Z.typ"),
        )

        assertNull(actual)
    }

    // ---- textEditsOnly ----

    fun testTextEditsOnly_keepsChangesAndTextDocumentEdits() {
        val edit = WorkspaceEdit(mapOf("file:///B.typ" to listOf<TextEdit>()))
        edit.documentChanges = listOf(
            Either.forLeft(
                TextDocumentEdit(VersionedTextDocumentIdentifier("file:///B.typ", 0), listOf())
            ),
        )

        val filtered = textEditsOnly(edit)

        assertEquals(edit.changes, filtered.changes)
        assertEquals(1, filtered.documentChanges.size)
        assertTrue(filtered.documentChanges.single().isLeft)
    }

    /**
     * A resource operation here would move the file out from under the platform, which is about to
     * rename it itself. `willRenameFiles` should never send one; the guard makes sure it cannot
     * cause a double rename if it does.
     */
    fun testTextEditsOnly_dropsResourceOperations() {
        val edit = WorkspaceEdit()
        edit.documentChanges = listOf(
            Either.forLeft(
                TextDocumentEdit(VersionedTextDocumentIdentifier("file:///B.typ", 0), listOf())
            ),
            Either.forRight(RenameFile("file:///A.typ", "file:///Z.typ")),
        )

        val filtered = textEditsOnly(edit)

        assertEquals(1, filtered.documentChanges.size)
        assertTrue(filtered.documentChanges.single().isLeft)
    }

    // ---- renameElement: applying the parked edits ----

    /**
     * The payoff: renaming `A.typ` rewrites `B.typ`'s import *and* renames the file, in one go.
     */
    fun testRenameElement_appliesParkedImportEditsAndRenamesFile() {
        val dir = Files.createTempDirectory("typst-will-rename")
        val target = dir.resolve("A.typ")
        val dependent = dir.resolve("B.typ")
        try {
            Files.writeString(target, "#let greeting = 1")
            Files.writeString(dependent, "#import \"A.typ\": *")
            val targetVf = refreshAndFind(target)
            val dependentVf = refreshAndFind(dependent)

            val psiFile = checkNotNull(PsiManager.getInstance(project).findFile(targetVf)) {
                "no PsiFile for A.typ"
            }

            // Stand in for what prepareRenaming parks after the willRenameFiles round trip.
            val parked = WorkspaceEdit()
            parked.documentChanges = listOf(
                Either.forLeft(
                    TextDocumentEdit(
                        VersionedTextDocumentIdentifier(dependent.toUri().toString(), 0),
                        listOf(TextEdit(Range(Position(0, 9), Position(0, 14)), "Z.typ")),
                    )
                ),
            )
            targetVf.putUserData(PENDING_IMPORT_EDITS, parked)

            WriteCommandAction.runWriteCommandAction(project) {
                processor.renameElement(psiFile, "Z.typ", emptyArray(), null)
            }

            val dependentDoc = FileDocumentManager.getInstance().getDocument(dependentVf)!!
            assertEquals("#import \"Z.typ\": *", dependentDoc.text)
            assertEquals("Z.typ", targetVf.name)
            assertNull("the parked edits must be consumed", targetVf.getUserData(PENDING_IMPORT_EDITS))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** With nothing parked — no client, a failed request, a cancelled progress — the rename still happens. */
    fun testRenameElement_withoutParkedEdits_stillRenamesFile() {
        val dir = Files.createTempDirectory("typst-will-rename-none")
        val target = dir.resolve("A.typ")
        try {
            Files.writeString(target, "#let greeting = 1")
            val targetVf = refreshAndFind(target)
            val psiFile = checkNotNull(PsiManager.getInstance(project).findFile(targetVf)) {
                "no PsiFile for A.typ"
            }

            WriteCommandAction.runWriteCommandAction(project) {
                processor.renameElement(psiFile, "Z.typ", emptyArray(), null)
            }

            assertEquals("Z.typ", targetVf.name)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun refreshAndFind(path: java.nio.file.Path): VirtualFile = checkNotNull(
        ApplicationManager.getApplication().runWriteAction<VirtualFile?> {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
        }) { "Could not resolve $path in the VFS" }
}

/** Test double returning a canned `workspace/willRenameFiles` answer. */
private class FakeWillRenameClient(private val response: WorkspaceEdit?) : LspClient {

    var callCount = 0
        private set

    @Suppress("UNCHECKED_CAST")
    override fun <Lsp4jResponse> sendRequestSync(
        timeoutMs: Int,
        lsp4jSender: (org.eclipse.lsp4j.services.LanguageServer) -> CompletableFuture<Lsp4jResponse>,
    ): Lsp4jResponse? {
        callCount++
        return response as Lsp4jResponse?
    }

    override val providerClass: Class<out LspIntegrationProvider>
        get() = TinymistLspServerSupportProvider::class.java
    override val project: Project get() = throw UnsupportedOperationException()
    override val descriptor: LspClientDescriptor get() = throw UnsupportedOperationException()
    override val state: LspServerState get() = LspServerState.Running
    override val initializeResult: InitializeResult? get() = null
    override fun sendNotification(lsp4jSender: (org.eclipse.lsp4j.services.LanguageServer) -> Unit) {}
    override suspend fun <Lsp4jResponse> sendRequest(
        lsp4jSender: (org.eclipse.lsp4j.services.LanguageServer) -> CompletableFuture<Lsp4jResponse>
    ): Lsp4jResponse? = null

    override fun getDocumentIdentifier(file: VirtualFile) = TextDocumentIdentifier(file.url)
    override fun getDocumentVersion(document: Document) = 0
}

package com.github.pndv.typstrenderer.lsp

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.Either3
import java.nio.file.Files
import java.util.concurrent.CompletableFuture

/**
 * Unit tests for [TypstLspRenameHandler].
 *
 * Scope: exercises pure-logic helpers and the `isAvailableOnDataContext` gate.
 * End-to-end rename flow tests (covering `prepareRename`/`rename` LSP requests,
 * timeout handling, multi-file edits via `documentChanges`, etc.) require a
 * mocked LSP server and are deferred to Batch 3 of the test-coverage plan.
 */
class TypstLspRenameHandlerTest : BasePlatformTestCase() {

    private val handler = TypstLspRenameHandler()


    fun testIsAvailable_whenFileIsNotTypst_returnsFalse() {
        val psiFile = myFixture.configureByText("test.txt", "not a typst file")
        val ctx = SimpleDataContext.builder()
            .add(CommonDataKeys.VIRTUAL_FILE, psiFile.virtualFile)
            .add(CommonDataKeys.PROJECT, project)
            .build()

        assertFalse(handler.isAvailableOnDataContext(ctx))
    }

    // Note: A "typst file + no LSP server → false" test is not reliable here because the
    // IntelliJ platform's LSP bridge auto-registers a server for the Tinymist provider when
    // a .typ file is opened in the test fixture, making findLspServer() return non-null.
    // That branch is better covered by a Batch 3 integration test with a controllable LSP mock.
    fun testIsAvailable_whenNoVirtualFileInContext_returnsFalse() {
        val ctx = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .build()

        assertFalse(handler.isAvailableOnDataContext(ctx))
    }

    fun testIsAvailable_whenNoProjectInContext_returnsFalse() {
        val psiFile = myFixture.configureByText("test.typ", "#let foo = 1")
        val ctx = SimpleDataContext.builder()
            .add(CommonDataKeys.VIRTUAL_FILE, psiFile.virtualFile)
            .build()

        assertFalse(handler.isAvailableOnDataContext(ctx))
    }

    fun testOffsetToLspPosition_atLineStart_returnsZeroCharacter() {
        val doc = DocumentImpl("line1\nline2\nline3")
        val pos = handler.offsetToLspPosition(doc, 0)
        assertEquals(0, pos.line)
        assertEquals(0, pos.character)
    }

    fun testOffsetToLspPosition_midLine_returnsCorrectCharacter() {
        val doc = DocumentImpl("hello world")
        val pos = handler.offsetToLspPosition(doc, 6) // 'w'
        assertEquals(0, pos.line)
        assertEquals(6, pos.character)
    }

    fun testOffsetToLspPosition_convertsCorrectlyAcrossLines() {
        val doc = DocumentImpl("abc\ndef\nghi")
        // offset 4 = 'd' on line 1 at character 0
        val pos1 = handler.offsetToLspPosition(doc, 4)
        assertEquals(1, pos1.line)
        assertEquals(0, pos1.character)

        // offset 9 = 'h' on line 2 at character 1
        val pos2 = handler.offsetToLspPosition(doc, 9)
        assertEquals(2, pos2.line)
        assertEquals(1, pos2.character)
    }

    // ---- extractCurrentName ----
    fun testExtractCurrentName_fromPrepareRenameResult_returnsPlaceholder() {
        val result = PrepareRenameResult(Range(Position(0, 0), Position(0, 3)), "foo")
        val doc = DocumentImpl("anything")
        assertEquals("foo", handler.extractCurrentName(result, doc))
    }

    fun testExtractCurrentName_fromRangeResult_extractsDocumentSlice() {
        val doc = DocumentImpl("#let greeting = hello")
        // Range covering "greeting" (offsets 5..13) → line 0, chars 5..13
        val range = Range(Position(0, 5), Position(0, 13))
        assertEquals("greeting", handler.extractCurrentName(range, doc))
    }

    fun testExtractCurrentName_fromRangeAcrossLines_extractsCorrectly() {
        val doc = DocumentImpl("line1\nfoo_bar\nline3")
        // "foo_bar" starts on line 1 char 0, ends line 1 char 7
        val range = Range(Position(1, 0), Position(1, 7))
        assertEquals("foo_bar", handler.extractCurrentName(range, doc))
    }

    fun testExtractCurrentName_fromUnknownType_returnsNull() {
        val doc = DocumentImpl("anything")
        assertNull(handler.extractCurrentName("unexpected string", doc))
        assertNull(handler.extractCurrentName(42, doc))
    }

    // ---- getWordAtCaret ----
    fun testGetWordAtCaret_onIdentifier_returnsIdentifier() {
        myFixture.configureByText("test.typ", "#let gre<caret>eting = 1")
        assertEquals("greeting", handler.getWordAtCaret(myFixture.editor))
    }

    fun testGetWordAtCaret_onUnderscoreIdentifier_includesUnderscore() {
        myFixture.configureByText("test.typ", "#let foo_<caret>bar = 1")
        assertEquals("foo_bar", handler.getWordAtCaret(myFixture.editor))
    }

    fun testGetWordAtCaret_betweenNonIdentifierChars_returnsDefaultSymbol() {
        myFixture.configureByText("test.typ", "  <caret>  ")
        assertEquals("symbol", handler.getWordAtCaret(myFixture.editor))
    }

    fun testGetWordAtCaret_atStartOfIdentifier_returnsIdentifier() {
        myFixture.configureByText("test.typ", "<caret>greeting")
        assertEquals("greeting", handler.getWordAtCaret(myFixture.editor))
    }

    // ---- findVirtualFile ----
    fun testFindVirtualFile_malformedUri_returnsNull() {
        assertNull(handler.findVirtualFile("not a uri at all %%%"))
    }

    fun testFindVirtualFile_validUriButNonexistentFile_returnsNull() {
        assertNull(handler.findVirtualFile("file:///definitely/does/not/exist/foo.typ"))
    }

    // ---- applyTextEdits (reverse-order offset preservation) ----
    fun testApplyTextEdits_reverseOrderApplication_preservesOffsets() {
        // `applyTextEdits` resolves URIs via LocalFileSystem, so we need a real on-disk file
        // (the default `temp://` VFS used by myFixture.configureByText won't resolve).
        val tempFile = Files.createTempFile("typst-rename-test", ".typ")
        try {
            Files.writeString(tempFile, "foo bar foo baz foo")
            val virtualFile =
                ApplicationManager.getApplication().runWriteAction<VirtualFile?> {
                    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(tempFile)
                } ?: fail("Could not resolve temp file in VFS")

            val uri = tempFile.toUri().toString()

            // Three edits on the same line, deliberately passed in forward order to verify
            // that the handler re-sorts them descending before applying.
            val edits = listOf(
                TextEdit(Range(Position(0, 0), Position(0, 3)), "XXX"),      // replace first "foo"
                TextEdit(Range(Position(0, 8), Position(0, 11)), "YYY"),     // replace second "foo"
                TextEdit(Range(Position(0, 16), Position(0, 19)), "ZZZ"),    // replace third "foo"
            )

            WriteCommandAction.runWriteCommandAction(project) {
                handler.applyTextEdits(uri, edits)
            }

            val document =
                FileDocumentManager.getInstance().getDocument(virtualFile as VirtualFile)!!
            assertEquals("XXX bar YYY baz ZZZ", document.text)
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    fun testApplyTextEdits_invalidUri_noOpNoCrash() {
        // Should log a warning and return without throwing.
        WriteCommandAction.runWriteCommandAction(project) {
            handler.applyTextEdits("file:///nonexistent/path/foo.typ", emptyList())
        }
    }


    override fun tearDown() {
        TestDialogManager.setTestDialog(null)
        TestDialogManager.setTestInputDialog(null)
        super.tearDown()
    }

    fun testRename_singleFileEdit_appliesChanges() {
        val tempFile = Files.createTempFile("typst-rename-test", ".typ")
        try {
            Files.writeString(tempFile, "#let foo = 1")
            val vf = ApplicationManager.getApplication().runWriteAction<VirtualFile?> {
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(tempFile)
            } ?: fail("Could not resolve temp file in VFS")

            myFixture.configureByText("current.typ", "#let foo<caret> = 1")

            val uri = tempFile.toUri().toString()
            val workspaceEdit = WorkspaceEdit(
                mapOf(
                    uri to listOf(TextEdit(Range(Position(0, 5), Position(0, 8)), "bar"))
                )
            )
            val fakeServer = FakeLspServer(
                Either3.forSecond<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>(
                    PrepareRenameResult(Range(Position(0, 5), Position(0, 8)), "foo")
                ),
                workspaceEdit,
            )
            TestDialogManager.setTestInputDialog { "bar" }

            handler.performRenameWithServer(project, myFixture.editor, myFixture.file.virtualFile, fakeServer)

            val doc = FileDocumentManager.getInstance().getDocument(vf as VirtualFile)!!
            assertEquals("#let bar = 1", doc.text)
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    fun testRename_multiFileViaDocumentChanges_appliesAllEdits() {
        val file1 = Files.createTempFile("typst-rename-a", ".typ")
        val file2 = Files.createTempFile("typst-rename-b", ".typ")
        try {
            Files.writeString(file1, "#let foo = 1")
            Files.writeString(file2, "foo + 2")
            val vf1 = ApplicationManager.getApplication().runWriteAction<VirtualFile?> {
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file1)
            } ?: fail("Could not resolve file1 in VFS")
            val vf2 = ApplicationManager.getApplication().runWriteAction<VirtualFile?> {
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file2)
            } ?: fail("Could not resolve file2 in VFS")

            myFixture.configureByText("current.typ", "#let foo<caret> = 1")

            val uri1 = file1.toUri().toString()
            val uri2 = file2.toUri().toString()
            val workspaceEdit = WorkspaceEdit()
            workspaceEdit.documentChanges = listOf(
                Either.forLeft(
                    TextDocumentEdit(
                        VersionedTextDocumentIdentifier(uri1, 0),
                        listOf(TextEdit(Range(Position(0, 5), Position(0, 8)), "bar")),
                    )
                ),
                Either.forLeft(
                    TextDocumentEdit(
                        VersionedTextDocumentIdentifier(uri2, 0),
                        listOf(TextEdit(Range(Position(0, 0), Position(0, 3)), "bar")),
                    )
                ),
            )
            val fakeServer = FakeLspServer(
                Either3.forSecond<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>(
                    PrepareRenameResult(Range(Position(0, 5), Position(0, 8)), "foo")
                ),
                workspaceEdit,
            )
            TestDialogManager.setTestInputDialog { "bar" }

            handler.performRenameWithServer(project, myFixture.editor, myFixture.file.virtualFile, fakeServer)

            val doc1 = FileDocumentManager.getInstance().getDocument(vf1 as VirtualFile)!!
            val doc2 = FileDocumentManager.getInstance().getDocument(vf2 as VirtualFile)!!
            assertEquals("#let bar = 1", doc1.text)
            assertEquals("bar + 2", doc2.text)
        } finally {
            Files.deleteIfExists(file1)
            Files.deleteIfExists(file2)
        }
    }

    fun testRename_userCancelsDialog_noChangesApplied() {
        myFixture.configureByText("test.typ", "#let foo<caret> = 1")
        val fakeServer = FakeLspServer(
            Either3.forSecond<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>(
                PrepareRenameResult(Range(Position(0, 5), Position(0, 8)), "foo")
            ),
        )
        TestDialogManager.setTestInputDialog { null }

        handler.performRenameWithServer(project, myFixture.editor, myFixture.file.virtualFile, fakeServer)

        assertEquals(1, fakeServer.callCount)  // only prepareRename was called
        assertEquals("#let foo = 1", myFixture.editor.document.text)
    }

    fun testRename_prepareRenameReturnsNull_showsInfoNoChanges() {
        myFixture.configureByText("test.typ", "#let foo<caret> = 1")
        val fakeServer = FakeLspServer(null)
        TestDialogManager.setTestDialog(TestDialog.OK)

        handler.performRenameWithServer(project, myFixture.editor, myFixture.file.virtualFile, fakeServer)

        assertEquals(1, fakeServer.callCount)
        assertEquals("#let foo = 1", myFixture.editor.document.text)
    }

    fun testRename_renameRequestThrows_showsErrorNoChanges() {
        myFixture.configureByText("test.typ", "#let foo<caret> = 1")
        val fakeServer = FakeLspServer(
            Either3.forSecond<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>(
                PrepareRenameResult(Range(Position(0, 5), Position(0, 8)), "foo")
            ),
            RuntimeException("timeout"),
        )
        TestDialogManager.setTestDialog(TestDialog.OK)
        TestDialogManager.setTestInputDialog { "bar" }

        handler.performRenameWithServer(project, myFixture.editor, myFixture.file.virtualFile, fakeServer)

        assertEquals(2, fakeServer.callCount)
        assertEquals("#let foo = 1", myFixture.editor.document.text)
    }

    fun testRename_renameReturnsNullWorkspaceEdit_showsInfoNoChanges() {
        myFixture.configureByText("test.typ", "#let foo<caret> = 1")
        val fakeServer = FakeLspServer(
            Either3.forSecond<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>(
                PrepareRenameResult(Range(Position(0, 5), Position(0, 8)), "foo")
            ),
            null,
        )
        TestDialogManager.setTestDialog(TestDialog.OK)
        TestDialogManager.setTestInputDialog { "bar" }

        handler.performRenameWithServer(project, myFixture.editor, myFixture.file.virtualFile, fakeServer)

        assertEquals(2, fakeServer.callCount)
        assertEquals("#let foo = 1", myFixture.editor.document.text)
    }

    fun testRename_workspaceEditWithResourceOperations_skipsUnsupportedOps() {
        myFixture.configureByText("test.typ", "#let foo<caret> = 1")
        val workspaceEdit = WorkspaceEdit()
        workspaceEdit.documentChanges = listOf(
            Either.forRight(RenameFile("file:///old.typ", "file:///new.typ")),
        )
        val fakeServer = FakeLspServer(
            Either3.forSecond<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>(
                PrepareRenameResult(Range(Position(0, 5), Position(0, 8)), "foo")
            ),
            workspaceEdit,
        )
        TestDialogManager.setTestInputDialog { "bar" }

        // Must not throw; resource ops are silently skipped
        handler.performRenameWithServer(project, myFixture.editor, myFixture.file.virtualFile, fakeServer)
    }

    fun testRename_sameNameEntered_treatedAsCancel() {
        myFixture.configureByText("test.typ", "#let foo<caret> = 1")
        val fakeServer = FakeLspServer(
            Either3.forSecond<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>(
                PrepareRenameResult(Range(Position(0, 5), Position(0, 8)), "foo")
            ),
        )
        // currentName will fall back to getWordAtCaret → "foo" (the word at caret)
        TestDialogManager.setTestInputDialog { "foo" }  // same as current

        handler.performRenameWithServer(project, myFixture.editor, myFixture.file.virtualFile, fakeServer)

        assertEquals(1, fakeServer.callCount)  // only prepareRename called
    }
}

/**
 * Test double for [LspServer]. Responses are consumed in call order.
 * Store a [Throwable] in [responses] to simulate a failed/timeout request.
 */
private class FakeLspServer(private vararg val responses: Any?) : LspServer {

    var callCount = 0
        private set

    @Suppress("UNCHECKED_CAST")
    override fun <Lsp4jResponse> sendRequestSync(
        timeoutMs: Int,
        lsp4jSender: (org.eclipse.lsp4j.services.LanguageServer) -> CompletableFuture<Lsp4jResponse>,
    ): Lsp4jResponse? {
        val response = responses.getOrNull(callCount++)
        if (response is Throwable) throw response
        return response as Lsp4jResponse?
    }

    override val providerClass: Class<out LspServerSupportProvider>
        get() = TinymistLspServerSupportProvider::class.java
    override val project: Project get() = throw UnsupportedOperationException()
    override val descriptor: LspServerDescriptor get() = throw UnsupportedOperationException()
    override val state: LspServerState get() = LspServerState.Running
    override val initializeResult: InitializeResult? get() = null
    override fun sendNotification(lsp4jSender: (org.eclipse.lsp4j.services.LanguageServer) -> Unit) {}
    override suspend fun <Lsp4jResponse> sendRequest(
        lsp4jSender: (org.eclipse.lsp4j.services.LanguageServer) -> CompletableFuture<Lsp4jResponse>
    ): Lsp4jResponse? = null

    override fun getDocumentIdentifier(file: VirtualFile) = TextDocumentIdentifier(file.url)
    override fun getDocumentVersion(document: Document) = 0
}

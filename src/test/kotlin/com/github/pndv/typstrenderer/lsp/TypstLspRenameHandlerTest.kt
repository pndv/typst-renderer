package com.github.pndv.typstrenderer.lsp

import com.github.pndv.typstrenderer.pluginRegisteredInTestPlatform
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
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientDescriptor
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.refactoring.rename.PsiElementRenameHandler
import com.intellij.refactoring.rename.RenameHandlerRegistry
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.Either3
import java.nio.file.Files
import java.util.concurrent.CompletableFuture

/**
 * Unit tests for [TypstLspRenameHandler].
 *
 * Covers the pure-logic helpers, the `isAvailableOnDataContext` gate, and the rename flow itself
 * driven through [TypstLspRenameHandler.performRenameWithClient] against [FakeLspClient].
 *
 * What cannot be covered here is anything needing a live tinymist: no client starts in a fixture,
 * because the platform gates `fileOpened` on `ProjectFileIndex.isInContent` while fixture files
 * live in the out-of-content `temp://` VFS. Response shapes used by the fake are taken from
 * tinymist's own source rather than guessed.
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

    /**
     * No tinymist client ever starts in the fixture — the platform gates `fileOpened` on
     * `ProjectFileIndex.isInContent` and fixture files live in the out-of-content `temp://` VFS —
     * so an otherwise-complete symbol-rename context still yields `false` here.
     */
    fun testIsAvailable_whenTypstFileInEditorButNoLspClient_returnsFalse() {
        if (!pluginRegisteredInTestPlatform()) return
        val psiFile = myFixture.configureByText("test.typ", "#let foo<caret> = 1")
        val ctx =
            SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.VIRTUAL_FILE, psiFile.virtualFile)
                .add(CommonDataKeys.EDITOR, myFixture.editor)
                .build()

        assertNotNull("precondition: the context is a symbol-rename context", handler.typstFileForSymbolRename(ctx))
        assertFalse(handler.isAvailableOnDataContext(ctx))
    }

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

    /**
     * Regression test: renaming a `.typ` file from the Project view used to be a silent no-op.
     *
     * That context carries the file but no editor. This handler claimed it anyway, and because
     * `RenameHandlerRegistry` only falls back to its default `PsiElementRenameHandler` when *no*
     * registered handler reports availability, the platform's file rename never ran; it dispatched
     * to the element-based `invoke()` overload instead, which cannot rename a file.
     */
    fun testIsAvailable_whenTypstFileButNoEditor_returnsFalse() {
        if (!pluginRegisteredInTestPlatform()) return
        val psiFile = myFixture.configureByText("test.typ", "#let foo = 1")
        val ctx =
            SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.VIRTUAL_FILE, psiFile.virtualFile)
                .add(CommonDataKeys.PSI_ELEMENT, psiFile)
                .build()

        assertFalse(
            "A Typst file with no editor is a file rename; the platform handler must own it",
            handler.isAvailableOnDataContext(ctx),
        )
    }

    /**
     * Positive control for the editor-less test above.
     *
     * This exercises [TypstLspRenameHandler.typstFileForSymbolRename] rather than
     * `isAvailableOnDataContext`, because the latter additionally requires a live tinymist client
     * and no tinymist client ever starts in the fixture: the platform gates `fileOpened` on
     * `ProjectFileIndex.isInContent`, and fixture files live in the out-of-content `temp://` VFS.
     */
    fun testTypstFileForSymbolRename_withEditorOnTypstFile_returnsFile() {
        if (!pluginRegisteredInTestPlatform()) return
        val psiFile = myFixture.configureByText("test.typ", "#let foo<caret> = 1")
        val ctx =
            SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.VIRTUAL_FILE, psiFile.virtualFile)
                .add(CommonDataKeys.EDITOR, myFixture.editor)
                .build()

        assertEquals(
            "A Typst file open in an editor is a symbol rename; this handler must claim it",
            psiFile.virtualFile,
            handler.typstFileForSymbolRename(ctx),
        )
    }

    /** The file-rename context that used to be swallowed, checked at the gate itself. */
    fun testTypstFileForSymbolRename_withoutEditor_returnsNull() {
        if (!pluginRegisteredInTestPlatform()) return
        val psiFile = myFixture.configureByText("test.typ", "#let foo = 1")
        val ctx =
            SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.VIRTUAL_FILE, psiFile.virtualFile)
                .add(CommonDataKeys.PSI_ELEMENT, psiFile)
                .build()

        assertNull(handler.typstFileForSymbolRename(ctx))
    }

    /** The gate resolves the file from the editor, so a non-Typst editor is declined. */
    fun testTypstFileForSymbolRename_withEditorOnNonTypstFile_returnsNull() {
        val psiFile = myFixture.configureByText("test.txt", "not a typst file")
        val ctx =
            SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.VIRTUAL_FILE, psiFile.virtualFile)
                .add(CommonDataKeys.EDITOR, myFixture.editor)
                .build()

        assertNull(handler.typstFileForSymbolRename(ctx))
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

    // LSP4J wraps the prepareRename response in an Either3. Unwrapping it is what keeps the
    // dialog seeded with the server's placeholder instead of a naive word scan at the caret.
    fun testExtractCurrentName_fromEither3PrepareRenameResult_returnsPlaceholder() {
        val wrapped = Either3.forSecond<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>(
            PrepareRenameResult(Range(Position(0, 9), Position(0, 14)), "A.typ")
        )
        assertEquals("A.typ", handler.extractCurrentName(wrapped, DocumentImpl("anything")))
    }

    fun testExtractCurrentName_fromEither3Range_extractsDocumentSlice() {
        val doc = DocumentImpl("#import \"A.typ\": *")
        val wrapped = Either3.forFirst<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>(
            Range(Position(0, 9), Position(0, 14))
        )
        assertEquals("A.typ", handler.extractCurrentName(wrapped, doc))
    }

    /** The default-behaviour arm carries no name, so the caller falls back to the caret word. */
    fun testExtractCurrentName_fromEither3DefaultBehaviour_returnsNull() {
        val wrapped = Either3.forThird<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>(
            PrepareRenameDefaultBehavior(true)
        )
        assertNull(handler.extractCurrentName(wrapped, DocumentImpl("anything")))
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
            val fakeClient = FakeLspClient(
                Either3.forSecond<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>(
                    PrepareRenameResult(Range(Position(0, 5), Position(0, 8)), "foo")
                ),
                workspaceEdit,
            )
            TestDialogManager.setTestInputDialog { "bar" }

            handler.performRenameWithClient(project, myFixture.editor, myFixture.file.virtualFile, fakeClient)

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
            val fakeClient = FakeLspClient(
                Either3.forSecond<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>(
                    PrepareRenameResult(Range(Position(0, 5), Position(0, 8)), "foo")
                ),
                workspaceEdit,
            )
            TestDialogManager.setTestInputDialog { "bar" }

            handler.performRenameWithClient(project, myFixture.editor, myFixture.file.virtualFile, fakeClient)

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
        val fakeClient = FakeLspClient(
            Either3.forSecond<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>(
                PrepareRenameResult(Range(Position(0, 5), Position(0, 8)), "foo")
            ),
        )
        TestDialogManager.setTestInputDialog { null }

        handler.performRenameWithClient(project, myFixture.editor, myFixture.file.virtualFile, fakeClient)

        assertEquals(1, fakeClient.callCount)  // only prepareRename was called
        assertEquals("#let foo = 1", myFixture.editor.document.text)
    }

    fun testRename_prepareRenameReturnsNull_showsInfoNoChanges() {
        myFixture.configureByText("test.typ", "#let foo<caret> = 1")
        val fakeClient = FakeLspClient(null)
        TestDialogManager.setTestDialog(TestDialog.OK)

        handler.performRenameWithClient(project, myFixture.editor, myFixture.file.virtualFile, fakeClient)

        assertEquals(1, fakeClient.callCount)
        assertEquals("#let foo = 1", myFixture.editor.document.text)
    }

    fun testRename_renameRequestThrows_showsErrorNoChanges() {
        myFixture.configureByText("test.typ", "#let foo<caret> = 1")
        val fakeClient = FakeLspClient(
            Either3.forSecond<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>(
                PrepareRenameResult(Range(Position(0, 5), Position(0, 8)), "foo")
            ),
            RuntimeException("timeout"),
        )
        TestDialogManager.setTestDialog(TestDialog.OK)
        TestDialogManager.setTestInputDialog { "bar" }

        handler.performRenameWithClient(project, myFixture.editor, myFixture.file.virtualFile, fakeClient)

        assertEquals(2, fakeClient.callCount)
        assertEquals("#let foo = 1", myFixture.editor.document.text)
    }

    fun testRename_renameReturnsNullWorkspaceEdit_showsInfoNoChanges() {
        myFixture.configureByText("test.typ", "#let foo<caret> = 1")
        val fakeClient = FakeLspClient(
            Either3.forSecond<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>(
                PrepareRenameResult(Range(Position(0, 5), Position(0, 8)), "foo")
            ),
            null,
        )
        TestDialogManager.setTestDialog(TestDialog.OK)
        TestDialogManager.setTestInputDialog { "bar" }

        handler.performRenameWithClient(project, myFixture.editor, myFixture.file.virtualFile, fakeClient)

        assertEquals(2, fakeClient.callCount)
        assertEquals("#let foo = 1", myFixture.editor.document.text)
    }

    fun testRename_resourceOperationForMissingFile_isSkippedNotThrown() {
        myFixture.configureByText("test.typ", "#let foo<caret> = 1")
        val workspaceEdit = WorkspaceEdit()
        workspaceEdit.documentChanges = listOf(
            Either.forRight(RenameFile("file:///nonexistent/old.typ", "file:///nonexistent/new.typ")),
        )
        val fakeClient = FakeLspClient(
            Either3.forSecond<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>(
                PrepareRenameResult(Range(Position(0, 5), Position(0, 8)), "foo")
            ),
            workspaceEdit,
        )
        TestDialogManager.setTestInputDialog { "bar" }

        // A file the VFS cannot resolve is logged and skipped rather than throwing.
        handler.performRenameWithClient(project, myFixture.editor, myFixture.file.virtualFile, fakeClient)
    }

    /**
     * With the caret on an import path, tinymist returns the path rewrites *and* a RenameFile
     * resource operation. Dropping the latter used to leave every rewritten import pointing at a
     * file that no longer existed, which breaks the build — a partial write is worse than none.
     */
    fun testRename_importPathEdit_appliesTextEditsAndRenamesFile() {
        val dir = Files.createTempDirectory("typst-rename-import")
        val target = dir.resolve("A.typ")
        val dependent = dir.resolve("B.typ")
        try {
            Files.writeString(target, "#let greeting = 1")
            Files.writeString(dependent, "#import \"A.typ\": *")
            val targetVf = ApplicationManager.getApplication().runWriteAction<VirtualFile?> {
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)
            } ?: fail("Could not resolve A.typ in VFS")
            val dependentVf = ApplicationManager.getApplication().runWriteAction<VirtualFile?> {
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dependent)
            } ?: fail("Could not resolve B.typ in VFS")

            // Exactly the shape tinymist emits: text edits first, the file rename last.
            val workspaceEdit = WorkspaceEdit()
            workspaceEdit.documentChanges = listOf(
                Either.forLeft(
                    TextDocumentEdit(
                        VersionedTextDocumentIdentifier(dependent.toUri().toString(), 0),
                        listOf(TextEdit(Range(Position(0, 9), Position(0, 14)), "Z.typ")),
                    )
                ),
                Either.forRight(
                    RenameFile(target.toUri().toString(), dir.resolve("Z.typ").toUri().toString())
                ),
            )

            handler.applyWorkspaceEdit(project, workspaceEdit)

            val dependentDoc = FileDocumentManager.getInstance().getDocument(dependentVf as VirtualFile)!!
            assertEquals("#import \"Z.typ\": *", dependentDoc.text)
            assertEquals(
                "the file itself must be renamed, not just the imports", "Z.typ", (targetVf as VirtualFile).name
            )
            assertTrue("Z.typ should exist on disk", Files.exists(dir.resolve("Z.typ")))
            assertFalse("A.typ should no longer exist", Files.exists(target))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** Renaming to a path with directories moves the file as well as renaming it. */
    fun testApplyResourceOperation_targetInAnotherDirectory_movesFile() {
        val dir = Files.createTempDirectory("typst-rename-move")
        val subDir = Files.createDirectory(dir.resolve("sub"))
        val source = dir.resolve("A.typ")
        try {
            Files.writeString(source, "#let greeting = 1")
            val sourceVf = ApplicationManager.getApplication().runWriteAction<VirtualFile?> {
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source)
            } ?: fail("Could not resolve A.typ in VFS")
            ApplicationManager.getApplication().runWriteAction {
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(subDir)
            }

            WriteCommandAction.runWriteCommandAction(project) {
                handler.applyResourceOperation(
                    RenameFile(source.toUri().toString(), subDir.resolve("Z.typ").toUri().toString())
                )
            }

            assertEquals("Z.typ", (sourceVf as VirtualFile).name)
            assertTrue("file should have moved into sub/", Files.exists(subDir.resolve("Z.typ")))
            assertFalse("original path should be gone", Files.exists(source))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** A target directory that does not exist must abort the rename, not invent directories. */
    fun testApplyResourceOperation_missingTargetDirectory_isSkipped() {
        val dir = Files.createTempDirectory("typst-rename-nodir")
        val source = dir.resolve("A.typ")
        try {
            Files.writeString(source, "#let greeting = 1")
            val sourceVf = ApplicationManager.getApplication().runWriteAction<VirtualFile?> {
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source)
            } ?: fail("Could not resolve A.typ in VFS")

            WriteCommandAction.runWriteCommandAction(project) {
                handler.applyResourceOperation(
                    RenameFile(
                        source.toUri().toString(),
                        dir.resolve("absent").resolve("Z.typ").toUri().toString(),
                    )
                )
            }

            assertEquals("A.typ", (sourceVf as VirtualFile).name)
            assertTrue("the original file must be left alone", Files.exists(source))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ---- (a) Rename driven from inside an #import statement ----

    /**
     * The import-path rename, driven through the real entry point with tinymist's response shape.
     *
     * What this can and cannot cover: deciding that the caret sits on an import path is tinymist's
     * job, so the fake server returns exactly what tinymist returns for that case — a placeholder
     * of the path string, then text edits plus a RenameFile operation (verified against
     * `crates/tinymist-query/src/prepare_rename.rs` and `rename.rs:48`). What is under test is the
     * plugin's half: seeding the dialog with the path, and applying *both* halves of the answer.
     */
    fun testRename_fromImportStatement_updatesImportAndRenamesFile() {
        val dir = Files.createTempDirectory("typst-rename-from-import")
        val target = dir.resolve("A.typ")
        val dependent = dir.resolve("B.typ")
        try {
            Files.writeString(target, "#let greeting = 1")
            Files.writeString(dependent, "#import \"A.typ\": *")
            val targetVf = ApplicationManager.getApplication().runWriteAction<VirtualFile?> {
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)
            } ?: fail("Could not resolve A.typ in VFS")
            val dependentVf = ApplicationManager.getApplication().runWriteAction<VirtualFile?> {
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dependent)
            } ?: fail("Could not resolve B.typ in VFS")

            // The caret sits on the path inside the import — the scenario under test.
            myFixture.configureByText("B.typ", "#import \"A<caret>.typ\": *")

            val workspaceEdit = WorkspaceEdit()
            workspaceEdit.documentChanges = listOf(
                Either.forLeft(
                    TextDocumentEdit(
                        VersionedTextDocumentIdentifier(dependent.toUri().toString(), 0),
                        listOf(TextEdit(Range(Position(0, 9), Position(0, 14)), "Z.typ")),
                    )
                ),
                Either.forRight(
                    RenameFile(target.toUri().toString(), dir.resolve("Z.typ").toUri().toString())
                ),
            )
            val fakeClient =
                FakeLspClient(
                    // tinymist hands back the whole path string as the placeholder, not a bare stem.
                    Either3.forSecond<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>(
                        PrepareRenameResult(Range(Position(0, 9), Position(0, 14)), "A.typ")
                    ),
                    workspaceEdit,
                )

            var prompt: String? = null
            TestDialogManager.setTestInputDialog { message ->
                prompt = message
                "Z.typ"
            }

            handler.performRenameWithClient(project, myFixture.editor, myFixture.file.virtualFile, fakeClient)

            assertTrue(
                "the dialog must be seeded with the import path, got: $prompt",
                prompt?.contains("A.typ") == true,
            )
            val dependentDoc = FileDocumentManager.getInstance().getDocument(dependentVf as VirtualFile)!!
            assertEquals("#import \"Z.typ\": *", dependentDoc.text)
            assertEquals("the imported file must be renamed too", "Z.typ", (targetVf as VirtualFile).name)
            assertFalse("A.typ should no longer exist", Files.exists(target))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ---- (b) Rename initiated by clicking the file in the Project view ----

    /**
     * The original regression, checked where it actually bit: the platform's handler *selection*.
     *
     * Asserting on `isAvailableOnDataContext` alone would not have caught it — the bug was that
     * saying "yes" there stops `RenameHandlerRegistry` from ever reaching its default file-rename
     * handler. This drives the registry itself with a Project-view-shaped context (a file and a
     * PSI element, deliberately no editor) and checks who wins.
     */
    fun testProjectViewContext_platformFileHandlerWinsNotOurs() {
        if (!pluginRegisteredInTestPlatform()) return
        val psiFile = myFixture.configureByText("A.typ", "#let greeting = 1")
        val ctx =
            SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.VIRTUAL_FILE, psiFile.virtualFile)
                .add(CommonDataKeys.PSI_ELEMENT, psiFile)
                .build()

        val selected = RenameHandlerRegistry.getInstance().getRenameHandler(ctx)

        assertNotNull("a file rename must find a handler", selected)
        assertFalse(
            "the LSP symbol-rename handler must not claim a file rename",
            selected is TypstLspRenameHandler,
        )
        assertInstanceOf(selected, PsiElementRenameHandler::class.java)
    }

    /** End to end: the handler the platform picks for that context really does rename the file. */
    fun testProjectViewRename_renamesTheFileOnDisk() {
        if (!pluginRegisteredInTestPlatform()) return
        val psiFile = myFixture.configureByText("A.typ", "#let greeting = 1")
        val ctx =
            SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.VIRTUAL_FILE, psiFile.virtualFile)
                .add(
                    CommonDataKeys.PSI_ELEMENT, psiFile
                ) // Unit-test hook on PsiElementRenameHandler: supplies the new name instead of a dialog.
                .add(PsiElementRenameHandler.DEFAULT_NAME, "Z.typ")
                .build()
        val selected =
            RenameHandlerRegistry.getInstance().getRenameHandler(ctx)
            ?: fail("no rename handler for a Project view context")

        WriteCommandAction.runWriteCommandAction(project) {
            (selected as com.intellij.refactoring.rename.RenameHandler).invoke(project, arrayOf(psiFile), ctx)
        }

        assertEquals("Z.typ", psiFile.virtualFile.name)
    }

    fun testRename_sameNameEntered_treatedAsCancel() {
        myFixture.configureByText("test.typ", "#let foo<caret> = 1")
        val fakeClient = FakeLspClient(
            Either3.forSecond<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>(
                PrepareRenameResult(Range(Position(0, 5), Position(0, 8)), "foo")
            ),
        ) // currentName comes from the server's placeholder → "foo"
        TestDialogManager.setTestInputDialog { "foo" }  // same as current

        handler.performRenameWithClient(project, myFixture.editor, myFixture.file.virtualFile, fakeClient)

        assertEquals(1, fakeClient.callCount)  // only prepareRename called
    }
}

/**
 * Test double for [LspClient]. Responses are consumed in call order.
 * Store a [Throwable] in [responses] to simulate a failed/timeout request.
 */
private class FakeLspClient(private vararg val responses: Any?) : LspClient {

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

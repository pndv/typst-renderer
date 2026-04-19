package com.github.pndv.typstrenderer.lsp

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PrepareRenameResult
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import java.nio.file.Files

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

    // ---- isAvailableOnDataContext ----

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

    // ---- offsetToLspPosition ----

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
                ApplicationManager.getApplication().runWriteAction<com.intellij.openapi.vfs.VirtualFile?> {
                    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(tempFile)
                } ?: fail("Could not resolve temp file in VFS")

            val uri = "file://" + tempFile.toAbsolutePath().toString()

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
                FileDocumentManager.getInstance().getDocument(virtualFile as com.intellij.openapi.vfs.VirtualFile)!!
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
}

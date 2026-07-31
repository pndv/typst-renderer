package com.github.pndv.typstrenderer.lsp

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for the pure-function core of [resolveTypstMainFile].
 *
 * The production `resolveTypstMainFile(Project)` is a thin wrapper that reads
 * `typstMainFile` out of `TypstProjectSettingsState`; testing the pure helper gives
 * validation coverage (blank / missing / wrong-extension / directory / valid) without
 * needing a `BasePlatformTestCase` fixture — same pattern as [TypstRootResolverTest].
 *
 * The temp directory and its fixture paths (a valid `.typ` file, a `.typ` directory, a
 * non-`.typ` file, and a non-existent path) are built once in `@BeforeClass` and torn
 * down once in `@AfterClass`. The fixtures are read-only by design, so sharing them
 * across the suite is safe and avoids a fresh tempdir per test.
 */
class TypstMainFileResolverTest {

    companion object {
        private var workDir: File? = null
        private lateinit var validMainFile: String
        private lateinit var nonTypFile: String
        private lateinit var typDirectory: String
        private lateinit var missingPath: String

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            workDir = Files.createTempDirectory("typst-main-file-resolve-test").toFile()
            validMainFile = File(workDir, "main.typ").apply { writeText("= Title") }.absolutePath
            nonTypFile = File(
                workDir, "notes.md"
            ).apply { writeText("not typst") }.absolutePath // A directory whose name ends in .typ — the extension check must not be
            // fooled into pinning a directory as the compile entry.
            typDirectory = File(workDir, "bundle.typ").apply { mkdirs() }.absolutePath
            missingPath = File(workDir, "does-not-exist.typ").absolutePath
        }

        @JvmStatic
        @AfterClass
        fun tearDownClass() {
            workDir?.deleteRecursively()
        }
    }

    @Test
    fun resolveTypstMainFile_blank_returnsNull() { // Blank means no pin — tinymist should compile the focused file, not be handed
        // an empty path.
        assertNull(resolveTypstMainFile(""))
    }

    @Test
    fun resolveTypstMainFile_whitespace_returnsNull() {
        assertNull("Whitespace-only path should be treated as unset", resolveTypstMainFile("   "))
    }

    @Test
    fun resolveTypstMainFile_missingFile_returnsNull() { // A stale or mistyped path should fall through rather than pin a non-existent
        // entry that tinymist could not compile.
        assertNull(
            "Missing file should fall through to null, not be pinned",
            resolveTypstMainFile(missingPath),
        )
    }

    @Test
    fun resolveTypstMainFile_nonTypExtension_returnsNull() { // Only .typ files can be a compile entry; a picked .md (or any other file) must
        // not be pinned.
        assertNull(
            "A file that is not a .typ should fall through",
            resolveTypstMainFile(nonTypFile),
        )
    }

    @Test
    fun resolveTypstMainFile_directoryWithTypSuffix_returnsNull() { // Extension alone is not enough — the entry must be a regular file.
        assertNull(
            "A directory whose name ends in .typ is not a valid compile entry",
            resolveTypstMainFile(typDirectory),
        )
    }

    @Test
    fun resolveTypstMainFile_validTypFile_returnsPath() {
        assertEquals(validMainFile, resolveTypstMainFile(validMainFile))
    }

    // ---- resolveTypstExportTarget: which file an export actually compiles ----

    @Test
    fun resolveTypstExportTarget_inProjectChapter_redirectsToPinnedMain() { // The point of the pin: a chapter is rendered as part of the whole document,
        // because tinymist.exportPdf compiles the exact path it is given and ignores
        // tinymist.pinMain.
        val chapter = Path.of(workDir!!.absolutePath, "chapters", "spelling-rules.typ")
        assertEquals(
            Path.of(validMainFile),
            resolveTypstExportTarget(validMainFile, chapter, focusedFileInProject = true),
        )
    }

    @Test
    fun resolveTypstExportTarget_outOfProjectFile_compilesItself() { // A pinned main is project-scoped and cannot #include a file outside the project
        // roots; such a file has its own folder-rooted client, so redirecting would render
        // the project's document in the standalone file's preview (issue #99).
        val standalone = Path.of(workDir!!.absolutePath, "resume.typ")
        assertEquals(
            "An out-of-content file must compile itself even when a main is pinned",
            standalone,
            resolveTypstExportTarget(validMainFile, standalone, focusedFileInProject = false),
        )
    }

    @Test
    fun resolveTypstExportTarget_noPinConfigured_compilesFocusedFile() {
        val chapter = Path.of(workDir!!.absolutePath, "chapters", "spelling-rules.typ")
        assertEquals(chapter, resolveTypstExportTarget("", chapter, focusedFileInProject = true))
    }

    @Test
    fun resolveTypstExportTarget_invalidPinConfigured_compilesFocusedFile() { // A stale/mistyped main must not silently redirect exports at a path that cannot
        // compile — fall back to the focused file, matching resolveTypstMainFile's contract.
        val chapter = Path.of(workDir!!.absolutePath, "chapters", "spelling-rules.typ")
        assertEquals(
            chapter,
            resolveTypstExportTarget(missingPath, chapter, focusedFileInProject = true),
        )
    }

    @Test
    fun resolveTypstMainFile_uppercaseExtension_returnsPath() { // Extension matching is case-insensitive: a file saved as MAIN.TYP on a
        // case-preserving filesystem is still a Typst file.
        val upper = File(workDir, "CHAPTER.TYP").apply { writeText("= C") }
        try {
            assertEquals(upper.absolutePath, resolveTypstMainFile(upper.absolutePath))
        } finally {
            upper.delete()
        }
    }
}

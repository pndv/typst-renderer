package com.github.pndv.typstrenderer.lsp

import com.github.pndv.typstrenderer.settings.TypstProjectSettingsState
import com.intellij.openapi.project.Project
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Files

/**
 * Tests for [resolveTypstFontPath].
 *
 * Unlike [resolveTypstRoot] there is no two-input fallback chain, so the
 * function does not need a pure-function core — the `Project` overload is
 * driven directly via a mocked [Project] that returns a real
 * [TypstProjectSettingsState] from `getService`.
 *
 * The temp directory and its three fixture paths (a real directory, a
 * regular file, a non-existent path) are built once in `@BeforeClass` and
 * torn down once in `@AfterClass`. The fixtures are read-only by design —
 * no test mutates them — so sharing across the suite is safe and avoids
 * five rounds of `Files.createTempDirectory` per run.
 */
class TypstFontPathResolverTest {

    companion object {
        private var workDir: File? = null
        private lateinit var validFontDir: String
        private lateinit var regularFilePath: String
        private lateinit var missingPath: String

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            workDir = Files.createTempDirectory("typst-font-path-resolve-test").toFile()
            validFontDir = File(workDir, "fonts").apply { mkdirs() }.absolutePath
            regularFilePath = File(workDir, "fonts.conf")
                .apply { writeText("not a directory") }.absolutePath
            missingPath = File(workDir, "does-not-exist").absolutePath
        }

        @JvmStatic
        @AfterClass
        fun tearDownClass() {
            workDir?.deleteRecursively()
        }
    }

    private val project = mock<Project>()

    private fun stubSettings(typstFontPath: String) {
        val settings = TypstProjectSettingsState().apply {
            this.typstFontPath = typstFontPath
        }
        whenever(project.getService(TypstProjectSettingsState::class.java))
            .thenReturn(settings)
    }

    @Test
    fun resolveTypstFontPath_blank_returnsNull() {
        // Blank means the user has never configured a font path — typst should
        // fall back to its built-in font discovery, not be passed an empty flag.
        stubSettings("")
        assertNull(resolveTypstFontPath(project))
    }

    @Test
    fun resolveTypstFontPath_whitespace_returnsNull() {
        // Whitespace-only is functionally indistinguishable from unset — a
        // user-typo that should not produce `--font-path "   "` on the CLI.
        stubSettings("   ")
        assertNull("Whitespace-only font path should be treated as unset", resolveTypstFontPath(project))
    }

    @Test
    fun resolveTypstFontPath_missingDir_returnsNull() {
        // Stale or mistyped path should silently fall through to no flag, the
        // same way [resolveTypstRoot] handles a missing override. Matches the
        // existing behaviour locked in by TypstRootResolverTest.
        stubSettings(missingPath)
        assertNull(
            "Missing directory should fall through to null, not produce a broken --font-path",
            resolveTypstFontPath(project),
        )
    }

    @Test
    fun resolveTypstFontPath_regularFile_returnsNull() {
        // A path that exists but is not a directory (e.g. the user picked a
        // font file by mistake) must also fall through — typst's --font-path
        // expects a directory to search, not a file.
        stubSettings(regularFilePath)
        assertNull(
            "Font path pointing to a regular file (not a directory) should fall through",
            resolveTypstFontPath(project),
        )
    }

    @Test
    fun resolveTypstFontPath_validDirectory_returnsPath() {
        stubSettings(validFontDir)
        assertEquals(validFontDir, resolveTypstFontPath(project))
    }
}

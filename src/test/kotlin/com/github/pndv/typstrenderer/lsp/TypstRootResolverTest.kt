package com.github.pndv.typstrenderer.lsp

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Tests for the pure-function core of [resolveTypstRoot].
 *
 * The production `resolveTypstRoot(Project)` is a thin wrapper that pulls
 * the configured override out of `TypstProjectSettingsState` and reads
 * `project.basePath`; testing the pure helper gives stage-by-stage
 * coverage of the resolution priority without needing a
 * `BasePlatformTestCase` fixture — same pattern as [BinaryResolutionTest].
 *
 * The temp directory and its fixture paths (an override directory, a
 * basePath directory, a non-existent path, and a regular file) are built
 * once in `@BeforeClass` and torn down once in `@AfterClass`. The fixtures
 * are read-only by design — no test mutates them — so sharing across the
 * suite is safe and avoids creating a fresh tempdir for every test.
 */
class TypstRootResolverTest {

    companion object {
        private var workDir: File? = null
        private lateinit var overrideRootPath: String
        private lateinit var basePath: String
        private lateinit var missingPath: String
        private lateinit var regularFilePath: String

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            workDir = Files.createTempDirectory("typst-root-resolve-test").toFile()
            overrideRootPath = File(workDir, "override-root").apply { mkdirs() }.absolutePath
            basePath = File(workDir, "base-path").apply { mkdirs() }.absolutePath
            missingPath = File(workDir, "does-not-exist").absolutePath
            regularFilePath = File(workDir, "not-a-directory")
                .apply { writeText("regular file") }.absolutePath
        }

        @JvmStatic
        @AfterClass
        fun tearDownClass() {
            workDir?.deleteRecursively()
        }
    }

    @Test
    fun resolveTypstRoot_configuredOverrideExists_winsOverBasePath() {
        val result = resolveTypstRoot(
            configuredOverride = overrideRootPath,
            projectBasePath = basePath,
        )

        assertEquals(
            "Stage 1 (configured override) should win over stage 2 (basePath)",
            overrideRootPath, result,
        )
    }

    @Test
    fun resolveTypstRoot_configuredOverrideBlank_fallsThroughToBasePath() {
        val result = resolveTypstRoot(
            configuredOverride = "",
            projectBasePath = basePath,
        )

        assertEquals(basePath, result)
    }

    @Test
    fun resolveTypstRoot_configuredOverrideWhitespace_fallsThroughToBasePath() {
        val result = resolveTypstRoot(
            configuredOverride = "   ",
            projectBasePath = basePath,
        )

        assertEquals("Whitespace-only override should be treated as unset", basePath, result)
    }

    @Test
    fun resolveTypstRoot_configuredOverridePointsToMissingDir_fallsThroughToBasePath() {
        val result = resolveTypstRoot(
            configuredOverride = missingPath,
            projectBasePath = basePath,
        )

        assertEquals(
            "Stale/mistyped override should silently fall through to basePath, not crash",
            basePath, result,
        )
    }

    @Test
    fun resolveTypstRoot_configuredOverridePointsToRegularFile_fallsThroughToBasePath() {
        val result = resolveTypstRoot(
            configuredOverride = regularFilePath,
            projectBasePath = basePath,
        )

        assertEquals(
            "An override pointing to a regular file (not a directory) should fall through",
            basePath, result,
        )
    }

    @Test
    fun resolveTypstRoot_configuredOverrideBlankAndBasePathNull_returnsNull() {
        val result = resolveTypstRoot(
            configuredOverride = "",
            projectBasePath = null,
        )

        assertNull("Should return null so callers know to skip --root entirely", result)
    }

    @Test
    fun resolveTypstRoot_configuredOverrideExistsAndBasePathNull_returnsOverride() {
        val result = resolveTypstRoot(
            configuredOverride = overrideRootPath,
            projectBasePath = null,
        )

        assertEquals(
            "Override should be honoured even when project has no basePath (light edit mode)",
            overrideRootPath, result,
        )
    }

    @Test
    fun resolveTypstRoot_configuredOverrideMissingAndBasePathNull_returnsNull() {
        val result = resolveTypstRoot(
            configuredOverride = missingPath,
            projectBasePath = null,
        )

        assertNull(
            "When override is invalid and there is no basePath fallback, return null",
            result,
        )
    }
}

package com.github.pndv.typstrenderer.lsp

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
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
 * Each test creates a real temporary directory rather than mocking
 * filesystem access, so the `File(...).isDirectory` guard in the
 * production code is exercised verbatim.
 */
class TypstRootResolverTest {

    private var workDir: File? = null

    @Before
    fun setUp() {
        workDir = Files.createTempDirectory("typst-root-resolve-test").toFile()
    }

    @After
    fun tearDown() {
        workDir?.deleteRecursively()
    }

    private fun realDir(name: String): File =
        File(workDir, name).apply { mkdirs() }

    @Test
    fun resolveTypstRoot_configuredOverrideExists_winsOverBasePath() {
        val override = realDir("override-root")
        val basePath = realDir("base-path").absolutePath

        val result = resolveTypstRoot(
            configuredOverride = override.absolutePath,
            projectBasePath = basePath,
        )

        assertEquals(
            "Stage 1 (configured override) should win over stage 2 (basePath)",
            override.absolutePath, result,
        )
    }

    @Test
    fun resolveTypstRoot_configuredOverrideBlank_fallsThroughToBasePath() {
        val basePath = realDir("base-path").absolutePath

        val result = resolveTypstRoot(
            configuredOverride = "",
            projectBasePath = basePath,
        )

        assertEquals(basePath, result)
    }

    @Test
    fun resolveTypstRoot_configuredOverrideWhitespace_fallsThroughToBasePath() {
        val basePath = realDir("base-path").absolutePath

        val result = resolveTypstRoot(
            configuredOverride = "   ",
            projectBasePath = basePath,
        )

        assertEquals("Whitespace-only override should be treated as unset", basePath, result)
    }

    @Test
    fun resolveTypstRoot_configuredOverridePointsToMissingDir_fallsThroughToBasePath() {
        val basePath = realDir("base-path").absolutePath
        val missingOverride = File(workDir, "does-not-exist").absolutePath

        val result = resolveTypstRoot(
            configuredOverride = missingOverride,
            projectBasePath = basePath,
        )

        assertEquals(
            "Stale/mistyped override should silently fall through to basePath, not crash",
            basePath, result,
        )
    }

    @Test
    fun resolveTypstRoot_configuredOverridePointsToRegularFile_fallsThroughToBasePath() {
        val basePath = realDir("base-path").absolutePath
        val asFile = File(workDir, "not-a-directory").apply { writeText("regular file") }

        val result = resolveTypstRoot(
            configuredOverride = asFile.absolutePath,
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
        val override = realDir("override-root")

        val result = resolveTypstRoot(
            configuredOverride = override.absolutePath,
            projectBasePath = null,
        )

        assertEquals(
            "Override should be honoured even when project has no basePath (light edit mode)",
            override.absolutePath, result,
        )
    }

    @Test
    fun resolveTypstRoot_configuredOverrideMissingAndBasePathNull_returnsNull() {
        val missingOverride = File(workDir, "does-not-exist").absolutePath

        val result = resolveTypstRoot(
            configuredOverride = missingOverride,
            projectBasePath = null,
        )

        assertNull(
            "When override is invalid and there is no basePath fallback, return null",
            result,
        )
    }
}

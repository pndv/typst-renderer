package com.github.pndv.typstrenderer.lsp

import com.github.pndv.typstrenderer.lsp.TinymistManager.Companion.isWindows
import com.github.pndv.typstrenderer.lsp.TinymistManager.Companion.resolveBinaryPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Tests for the 3-stage binary resolution fallback in [TinymistManager].
 *
 * The production [TinymistManager.resolveTinymistPath] and
 * [TinymistManager.resolveTypstPath] are thin wrappers over [resolveBinaryPath];
 * testing the pure helper gives us stage-by-stage priority coverage without
 * needing a `BasePlatformTestCase` fixture.
 *
 * Cross-platform: on Windows [com.github.pndv.typstrenderer.lsp.TinymistManager.Companion.isBinaryExecutable] recognises the `.exe` extension,
 * so [executableFile] appends it automatically. On Unix the POSIX execute bit is set.
 */
class BinaryResolutionTest {

    private var workDir: File? = null

    @Before
    fun setUp() {
        workDir = Files.createTempDirectory("binary-resolve-test").toFile()
    }

    @After
    fun tearDown() {
        workDir?.deleteRecursively()
    }

    private fun executableFile(name: String): File {
        val fileName = if (isWindows()) "$name.exe" else name
        return File(workDir, fileName).apply {
            writeText(if (isWindows()) "" else "#!/bin/sh\n")
            if (!isWindows()) setExecutable(true, false)
        }
    }

    @Test
    fun resolveBinary_userConfiguredPathValid_returnsConfiguredPath() {
        val configured = executableFile("configured-binary")
        val pathHit = executableFile("path-binary").absolutePath
        val downloaded = executableFile("downloaded-binary")

        val result = resolveBinaryPath(
            configuredPath = configured.absolutePath,
            findOnPath = { pathHit },
            downloadedBinary = downloaded,
        )

        assertEquals(
            "Stage 1 (configured) should win over stages 2 and 3",
            configured.absolutePath, result,
        )
    }

    @Test
    fun resolveBinary_userConfiguredPathBlank_fallsThroughToPathLookup() {
        val pathHit = executableFile("path-binary").absolutePath

        val result = resolveBinaryPath(
            configuredPath = "",
            findOnPath = { pathHit },
            downloadedBinary = File(workDir, "nonexistent"),
        )

        assertEquals(pathHit, result)
    }

    @Test
    fun resolveBinary_userConfiguredPathPointsToMissingFile_fallsThroughToPathLookup() {
        val pathHit = executableFile("path-binary").absolutePath

        val result = resolveBinaryPath(
            configuredPath = File(workDir, "does-not-exist").absolutePath,
            findOnPath = { pathHit },
            downloadedBinary = File(workDir, "nonexistent"),
        )

        assertEquals(
            "Non-existent configured path should behave like unset, not crash",
            pathHit, result,
        )
    }

    @Test
    fun resolveBinary_foundOnSystemPathOrWellKnownDir_returnsThatPath() {
        val pathHit = executableFile("somewhere-on-path").absolutePath

        val result = resolveBinaryPath(
            configuredPath = "",
            findOnPath = { pathHit },
            downloadedBinary = File(workDir, "nonexistent"),
        )

        assertEquals(pathHit, result)
    }

    @Test
    fun resolveBinary_foundAsDownloadedBinary_returnsDownloadedPath() {
        val downloaded = executableFile("downloaded-binary")

        val result = resolveBinaryPath(
            configuredPath = "",
            findOnPath = { null },
            downloadedBinary = downloaded,
        )

        assertEquals(downloaded.absolutePath, result)
    }

    @Test
    fun resolveBinary_notFoundAnywhere_returnsNull() {
        val result = resolveBinaryPath(
            configuredPath = "",
            findOnPath = { null },
            downloadedBinary = File(workDir, "nonexistent"),
        )

        assertNull("Should return null so caller triggers download prompt", result)
    }
}

package com.github.pndv.typstrenderer.lsp

import com.github.pndv.typstrenderer.lsp.TinymistManager.Companion.isWindows
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests for archive extraction in [TypstDownloadService].
 *
 * Covers Batch 2 of the test-coverage plan:
 *  - D.62  Unix tar.xz: extracts `typst` binary from the archive
 *  - D.63  Windows zip: finds `typst.exe` in a zip and extracts it
 *  - D.64  tar non-zero exit → IOException
 *  - D.65  zip missing typst.exe → IOException
 *  - D.66  tempExtractDir cleaned up on success (tar.xz path)
 *  - D.67  tempExtractDir cleaned up on failure (tar.xz path)
 *
 * Archive fixtures are generated on the fly in the workspace tempdir so tests don't
 * depend on binary files checked into the repo. The tar.xz tests invoke the host's
 * `tar` command (same as the production code), so they only run on Unix hosts; the
 * zip tests use pure Java and run everywhere.
 */
class ArchiveExtractionTest {

    private lateinit var workDir: File
    private lateinit var service: TypstDownloadService

    @Before
    fun setUp() {
        workDir = Files.createTempDirectory("typst-extract-test").toFile()
        service = TypstDownloadService::class.java.getDeclaredConstructor().newInstance()
    }

    @After
    fun tearDown() {
        workDir.deleteRecursively()
    }

    // ---- Helpers to build fixtures ----

    private fun buildTarXzFixture(archivePath: File, innerDir: String, innerFileName: String, content: String) {
        assumeTrue("Unix-only fixture builder (requires tar+xz)", !isWindows())
        val stage = File(workDir, "stage-${System.nanoTime()}")
        stage.mkdirs()
        try {
            val innerRoot = File(stage, innerDir).apply { mkdirs() }
            File(innerRoot, innerFileName).writeText(content)

            val proc = ProcessBuilder("tar", "cJf", archivePath.absolutePath, innerDir)
                .directory(stage)
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText()
            val exit = proc.waitFor()
            if (exit != 0) fail("tar cJf failed (exit=$exit): $out")
        } finally {
            stage.deleteRecursively()
        }
    }

    private fun buildZipFixture(archivePath: File, entries: Map<String, String>) {
        ZipOutputStream(archivePath.outputStream().buffered()).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
    }

    // ---- D.62  tar.xz extraction (Unix-only, uses host tar) ----

    @Test
    fun extractFromTarXz_validArchive_extractsTypstBinary() {
        assumeTrue("Unix-only — uses `tar` command", !isWindows())

        val archive = File(workDir, "typst-test.tar.xz")
        buildTarXzFixture(archive, "typst-x86_64-apple-darwin", "typst", "#!/bin/sh\necho fake typst\n")

        val target = File(workDir, "typst-out")
        service.extractFromTarXz(archive, target)

        assertTrue("target binary should exist at ${target.absolutePath}", target.exists())
        assertTrue(target.readText().contains("fake typst"))
    }

    // ---- D.63  zip extraction (cross-platform) ----

    @Test
    fun extractFromZip_validArchive_extractsTypstExe() {
        val archive = File(workDir, "typst-test.zip")
        buildZipFixture(
            archive,
            mapOf(
                "typst-x86_64-pc-windows-msvc/LICENSE" to "MIT",
                "typst-x86_64-pc-windows-msvc/typst.exe" to "FAKE_EXE_BYTES",
            ),
        )

        val target = File(workDir, "typst-out.exe")
        service.extractFromZip(archive, target)

        assertTrue(target.exists())
        assertEquals("FAKE_EXE_BYTES", target.readText())
    }

    // ---- D.64  tar non-zero exit → IOException ----

    @Test
    fun extractFromTarXz_corruptArchive_throwsIoException() {
        assumeTrue("Unix-only", !isWindows())

        val archive = File(workDir, "bogus.tar.xz").apply {
            writeText("this is not a valid tar.xz archive")
        }

        try {
            service.extractFromTarXz(archive, File(workDir, "out"))
            fail("Expected IOException for invalid tar.xz")
        } catch (expected: IOException) {
            assertTrue(
                "Error message should indicate tar failure, got: ${expected.message}",
                expected.message?.contains("tar", ignoreCase = true) == true ||
                        expected.message?.contains("extraction", ignoreCase = true) == true,
            )
        }
    }

    // ---- Extra: tar.xz with no `typst` file inside → IOException ----

    @Test
    fun extractFromTarXz_archiveMissingTypstBinary_throwsIoException() {
        assumeTrue("Unix-only", !isWindows())

        val archive = File(workDir, "no-typst.tar.xz")
        // Build an archive that has a file named something else
        buildTarXzFixture(archive, "some-dir", "not-typst", "contents")

        try {
            service.extractFromTarXz(archive, File(workDir, "out"))
            fail("Expected IOException when typst binary is absent from archive")
        } catch (expected: IOException) {
            assertTrue(
                "Error message should mention missing typst, got: ${expected.message}",
                expected.message?.contains("typst") == true,
            )
        }
    }

    // ---- D.65  zip missing typst.exe → IOException ----

    @Test
    fun extractFromZip_archiveMissingTypstExe_throwsIoException() {
        val archive = File(workDir, "no-exe.zip")
        buildZipFixture(archive, mapOf("some-dir/README.txt" to "nothing useful here"))

        try {
            service.extractFromZip(archive, File(workDir, "out.exe"))
            fail("Expected IOException when typst.exe is absent from zip")
        } catch (expected: IOException) {
            assertTrue(
                "Error message should mention typst.exe, got: ${expected.message}",
                expected.message?.contains("typst.exe") == true,
            )
        }
    }

    // ---- D.66  tempExtractDir cleaned up on success ----

    @Test
    fun extractFromTarXz_cleansUpTempExtractDirOnSuccess() {
        assumeTrue("Unix-only", !isWindows())

        val archive = File(workDir, "cleanup-success.tar.xz")
        buildTarXzFixture(archive, "typst-linux-x64", "typst", "ok")

        service.extractFromTarXz(archive, File(workDir, "out"))

        // The production code creates `typst-extract-tmp` as a sibling of the archive.
        val tempExtractDir = File(archive.parent, "typst-extract-tmp")
        assertFalse(
            "typst-extract-tmp should be deleted after successful extraction, but still exists at ${tempExtractDir.absolutePath}",
            tempExtractDir.exists(),
        )
    }

    // ---- D.67  tempExtractDir cleaned up on failure ----

    @Test
    fun extractFromTarXz_cleansUpTempExtractDirOnFailure() {
        assumeTrue("Unix-only", !isWindows())

        val archive = File(workDir, "cleanup-failure.tar.xz").apply {
            writeText("corrupt")
        }

        try {
            service.extractFromTarXz(archive, File(workDir, "out"))
            fail("Expected IOException")
        } catch (_: IOException) {
            // expected
        }

        val tempExtractDir = File(archive.parent, "typst-extract-tmp")
        assertFalse(
            "typst-extract-tmp should be deleted after failed extraction",
            tempExtractDir.exists(),
        )
    }

    // ---- extractTypstBinary dispatcher ----

    @Test
    fun extractTypstBinary_onUnix_dispatchesToTarXz() {
        assumeTrue("Unix-only", !isWindows())

        val archive = File(workDir, "dispatch.tar.xz")
        buildTarXzFixture(archive, "some-dir", "typst", "dispatched ok")

        val target = File(workDir, "dispatched-out")
        service.extractTypstBinary(archive, target)

        assertTrue(target.exists())
        assertTrue(target.readText().contains("dispatched ok"))
    }
}

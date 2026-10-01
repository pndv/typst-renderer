package com.github.pndv.typstrenderer.lsp

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for [TinymistDownloadService.atomicMove].
 *
 * Covers the file-move portion of the download flow (the HTTP mechanics
 * are intentionally not tested — we only care that the post-download
 * temp-file-to-target move behaves correctly).
 */
class AtomicMoveTest {

    private lateinit var workDir: File

    @Before
    fun setUp() {
        workDir = Files.createTempDirectory("atomic-move-test").toFile()
    }

    @After
    fun tearDown() {
        workDir.deleteRecursively()
    }

    @Test
    fun atomicMove_targetDoesNotExist_movesTempToTarget() {
        val temp = File(workDir, "download.tmp").apply { writeText("payload") }
        val target = File(workDir, "final.bin")

        TinymistDownloadService.atomicMove(temp, target)

        assertFalse("temp should be gone after move", temp.exists())
        assertTrue("target should exist after move", target.exists())
        assertEquals("payload", target.readText())
    }

    @Test
    fun atomicMove_targetExists_overwritesTarget() {
        val temp = File(workDir, "download.tmp").apply { writeText("new content") }
        val target = File(workDir, "final.bin").apply { writeText("stale content") }

        TinymistDownloadService.atomicMove(temp, target)

        assertFalse(temp.exists())
        assertTrue(target.exists())
        assertEquals("new content", target.readText())
    }

    @Test
    fun atomicMove_acrossDirectories_resultsInTargetWithCorrectContent() {
        val srcDir = File(workDir, "src").apply { mkdirs() }
        val dstDir = File(workDir, "dst").apply { mkdirs() }
        val temp = File(srcDir, "download.tmp").apply { writeText("xfer") }
        val target = File(dstDir, "final.bin")

        TinymistDownloadService.atomicMove(temp, target)

        assertFalse(temp.exists())
        assertTrue(target.exists())
        assertEquals("xfer", target.readText())
    }

    /**
     * Re-downloading from the settings while a language server runs: on Windows a running binary
     * can be neither deleted nor overwritten, so a direct replacement fails and the old file has
     * to be moved aside first. The failure is injected
     * because a live process holding a file cannot be staged portably — verified separately that
     * Windows does refuse the delete and does allow the rename.
     */
    @Test
    fun atomicMove_targetCannotBeReplacedInPlace_movesTheOldBinaryAside() {
        val temp = File(workDir, "download.tmp").apply { writeText("new binary") }
        val target = File(workDir, "tinymist.exe").apply { writeText("running binary") }
        val directReplaceAttempts = AtomicInteger()

        TinymistDownloadService.atomicMove(
            temp, target
        ) { source, destination -> // Fails the first time, as it would against a live process; the retry runs against
            // the freed name and behaves normally.
            if (directReplaceAttempts.getAndIncrement() == 0) false else source.renameTo(destination)
        }

        assertEquals("the move-aside path should have been taken", 2, directReplaceAttempts.get())
        assertEquals("new binary", target.readText())
        assertFalse("the staged download should be consumed", temp.exists())
        assertEquals(
            "an old binary nothing is running should not be left behind",
            0,
            workDir.listFiles { file -> file.name.contains(".replaced-") }.orEmpty().size,
        )
    }

    @Test
    fun cleanUpReplacedBinaries_removesTheAsideFilesAndLeavesTheBinary() {
        val target = File(workDir, "tinymist.exe").apply { writeText("current") }
        File(workDir, "tinymist.exe.replaced-1").writeText("old")
        File(workDir, "tinymist.exe.replaced-2").writeText("older")
        val unrelated = File(workDir, "notes.txt").apply { writeText("keep me") }

        TinymistDownloadService.cleanUpReplacedBinaries(target)

        assertTrue("the binary in use must survive the sweep", target.exists())
        assertTrue(unrelated.exists())
        assertEquals(0, workDir.listFiles { file -> file.name.contains(".replaced-") }.orEmpty().size)
    }

    /**
     * If the new binary cannot be put in place after the old one was moved aside, the old one
     * goes back. Leaving no tinymist at all would break a working setup on the strength of a
     * failed background update the user never asked for.
     */
    @Test
    fun atomicMove_replacementFailsEntirely_restoresTheOriginalBinary() {
        val temp = File(workDir, "download.tmp").apply { writeText("new binary") }
        val target = File(workDir, "tinymist.exe").apply { writeText("running binary") }

        try {
            TinymistDownloadService.atomicMove(temp, target) { _, _ -> false }
            fail("expected the failed replacement to be reported")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("tinymist.exe"))
        }

        assertTrue("the original binary should be back in place", target.exists())
        assertEquals("running binary", target.readText())
        assertEquals(0, workDir.listFiles { file -> file.name.contains(".replaced-") }.orEmpty().size)
    }
}

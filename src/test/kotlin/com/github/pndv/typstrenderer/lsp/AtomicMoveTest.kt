package com.github.pndv.typstrenderer.lsp

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

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
}

package com.github.pndv.typstrenderer.lsp

import com.intellij.openapi.util.SystemInfo
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Tests for keeping tinymist in step with the version this plugin pins: the prompt decision for a
 * tinymist the user installed, the versioned layout that makes a downloaded one follow the pin,
 * and the sweep that removes downloads made for earlier pins.
 *
 * All pure functions or plain file operations, so fixture-free.
 */
class TinymistVersionCheckTest {

    private lateinit var downloadDir: File

    @Before
    fun setUp() {
        downloadDir = Files.createTempDirectory("tinymist-bin").toFile()
    }

    @After
    fun tearDown() {
        downloadDir.deleteRecursively()
    }

    private fun v(text: String) = TinymistVersion.parse(text)!!

    private fun decide(
        installed: String,
        pinned: String = "0.15.8",
        kind: TinymistInstallKind = TinymistInstallKind.USER_MANAGED,
        promptsEnabled: Boolean = true,
        skipped: String? = null,
    ) = decideVersionAction(kind, v(installed), v(pinned), promptsEnabled, skipped?.let(::v))

    // ---- whether to prompt ----

    @Test
    fun aUserManagedTinymistBehindThePinIsReported() {
        assertEquals(TinymistVersionAction.Prompt, decide(installed = "0.15.2"))
    }

    @Test
    fun aReleaseCandidateOfThePinnedVersionCountsAsBehindIt() {
        assertEquals(TinymistVersionAction.Prompt, decide(installed = "0.15.8-rc1"))
    }

    @Test
    fun aUserManagedTinymistOnThePinIsInStep() {
        assertEquals(TinymistVersionAction.InStep, decide(installed = "0.15.8"))
    }

    @Test
    fun aUserManagedTinymistAheadOfThePinIsLeftAlone() { // Running something newer than the plugin was tested with is the user's informed choice.
        assertTrue(decide(installed = "0.16.0") is TinymistVersionAction.Ignore)
    }

    @Test
    fun aPluginManagedTinymistIsNeverPromptedAbout() { // It lives in a folder named after the pin, so it cannot be behind it.
        assertTrue(
            decide(
                installed = "0.15.2", kind = TinymistInstallKind.PLUGIN_MANAGED
            ) is TinymistVersionAction.Ignore
        )
    }

    @Test
    fun switchingPromptsOffSilencesThem() {
        assertTrue(decide(installed = "0.15.2", promptsEnabled = false) is TinymistVersionAction.Ignore)
    }

    @Test
    fun aSkippedPinIsNotRaisedAgain() {
        assertTrue(decide(installed = "0.15.2", skipped = "0.15.8") is TinymistVersionAction.Ignore)
    }

    @Test
    fun aPluginUpdateThatMovesThePinRaisesItAfresh() { // "Skip this version" means this version, not every version from here on.
        assertEquals(TinymistVersionAction.Prompt, decide(installed = "0.15.2", pinned = "0.15.10", skipped = "0.15.8"))
    }

    // ---- where a downloaded tinymist lives ----

    @Test
    fun theDownloadLivesInAFolderNamedAfterThePin() {
        assertEquals(
            File(File(downloadDir, "0.15.8"), "tinymist.exe"),
            TinymistManager.managedBinaryPath(downloadDir, v("0.15.8"), windows = true),
        )
        assertEquals(
            File(File(downloadDir, "0.15.8"), "tinymist"),
            TinymistManager.managedBinaryPath(downloadDir, v("0.15.8"), windows = false),
        )
    }

    @Test
    fun aNewPinNamesAPathThatDoesNotExistYet() { // The whole mechanism: moving the pin makes the resolved binary "missing", which is what
        // sends the ordinary first-install download off to fetch the pinned version.
        val old = TinymistManager.managedBinaryPath(downloadDir, v("0.15.2"), windows = true)
        old.parentFile.mkdirs()
        old.writeText("0.15.2 binary")

        val current = TinymistManager.managedBinaryPath(downloadDir, v("0.15.8"), windows = true)

        assertNotEquals(old, current)
        assertFalse(current.exists())
    }

    @Test
    fun withoutAPinTheDownloadKeepsTheUnversionedLayout() {
        assertEquals(
            File(downloadDir, "tinymist"), TinymistManager.managedBinaryPath(downloadDir, null, windows = false)
        )
    }

    // ---- which binary is the plugin's own ----

    @Test
    fun aBinaryInsideTheDownloadFolderIsPluginManaged() {
        val binary = TinymistManager.managedBinaryPath(downloadDir, v("0.15.8"), windows = false)
        assertTrue(
            isPluginManagedBinary(
                binary.absolutePath, downloadDir
            )
        ) // The unversioned layout from before still counts as the plugin's own.
        assertTrue(isPluginManagedBinary(File(downloadDir, "tinymist").absolutePath, downloadDir))
    }

    @Test
    fun aBinaryElsewhereOnDiskIsTheUsersOwn() {
        assertFalse(isPluginManagedBinary(File("/usr/local/bin/tinymist").absolutePath, downloadDir))
        assertFalse(isPluginManagedBinary(File(downloadDir.parentFile, "tinymist").absolutePath, downloadDir))
    }

    @Test
    fun windowsPathCasingDoesNotDisguiseThePluginsOwnBinary() {
        Assume.assumeTrue(SystemInfo.isWindows)
        val binary = TinymistManager.managedBinaryPath(downloadDir, v("0.15.8"), windows = true)
        assertTrue(isPluginManagedBinary(binary.absolutePath.uppercase(), downloadDir))
    }

    // ---- sweeping downloads made for earlier pins ----

    private fun file(path: String, text: String = "x"): File =
        File(downloadDir, path).apply { parentFile.mkdirs(); writeText(text) }

    @Test
    fun theSweepRemovesEveryEarlierDownloadAndKeepsThePinnedOne() {
        val current = file("0.15.8/tinymist.exe", "pinned")
        val legacy = file("tinymist.exe", "unversioned layout")
        val movedAside = file("tinymist.exe.replaced-1789951122890")
        val interrupted = file("tinymist.exe.download")
        val olderPin = file("0.15.2/tinymist.exe")

        TinymistManager.cleanUpStaleManagedBinaries(downloadDir, current, preserve = null)

        assertEquals("pinned", current.readText())
        for (stale in listOf(legacy, movedAside, interrupted, olderPin.parentFile)) {
            assertFalse("${stale.name} should have been removed", stale.exists())
        }
    }

    @Test
    fun theSweepTouchesNothingThePluginDidNotCreate() {
        val current = file("0.15.8/tinymist.exe")
        val notes = file("notes.txt")
        val otherFolder = file("fonts/Inter.ttf")

        TinymistManager.cleanUpStaleManagedBinaries(downloadDir, current, preserve = null)

        assertTrue(notes.exists())
        assertTrue("a folder not named after a version is not ours", otherFolder.exists())
    }

    @Test
    fun theSweepKeepsADownloadTheSettingsPointAt() { // Someone who set the tinymist path to a plugin download would otherwise have it deleted
        // from under the setting.
        val current = file("0.15.8/tinymist.exe")
        val configured = file("0.15.2/tinymist.exe", "still configured")

        TinymistManager.cleanUpStaleManagedBinaries(downloadDir, current, preserve = configured)

        assertEquals("still configured", configured.readText())
    }

    @Test
    fun theSweepDoesNothingInTheUnversionedLayout() { // With no pin there is no "current version" to compare the others against.
        val current = file("tinymist.exe", "current")
        val versioned = file("0.15.2/tinymist.exe")

        TinymistManager.cleanUpStaleManagedBinaries(downloadDir, current, preserve = null)

        assertTrue(current.exists())
        assertTrue(versioned.exists())
    }
}

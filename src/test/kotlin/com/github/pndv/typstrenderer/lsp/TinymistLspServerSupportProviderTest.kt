package com.github.pndv.typstrenderer.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure decision-table of [TinymistLspServerSupportProvider.fileOpened].
 *
 * `fileOpened` itself touches IDE singletons (ApplicationManager, the LSP
 * `LspServerStarter`, NotificationGroupManager) — wiring that's exercised
 * manually on every plugin start. The branching logic, however, is captured
 * in the pure [decideLspAction] function and is what we cover here. Same
 * fixture-free pattern as [TypstRootResolverTest].
 */
class TinymistLspServerSupportProviderTest {

    @Test
    fun decideLspAction_unitTestMode_skipsRegardlessOfFileTypeAndPath() {
        // Unit-test mode wins over everything: even a Typst file with a
        // resolved binary should produce Skip so we don't accidentally start
        // an LSP process from inside another plugin's tests.
        val action = decideLspAction(
            isUnitTestMode = true,
            isTypstFile = true,
            tinymistPath = "/usr/local/bin/tinymist",
        )

        assertSame(LspStartAction.Skip, action)
    }

    @Test
    fun decideLspAction_notTypstFile_skips() {
        val action = decideLspAction(
            isUnitTestMode = false,
            isTypstFile = false,
            tinymistPath = "/usr/local/bin/tinymist",
        )

        assertSame(
            "Non-Typst files must never trigger LSP start or auto-download",
            LspStartAction.Skip, action,
        )
    }

    @Test
    fun decideLspAction_typstFileAndBinaryResolved_startsServer() {
        val path = "/usr/local/bin/tinymist"

        val action = decideLspAction(
            isUnitTestMode = false,
            isTypstFile = true,
            tinymistPath = path,
        )

        assertTrue("Expected StartServer", action is LspStartAction.StartServer)
        assertEquals(path, (action as LspStartAction.StartServer).tinymistPath)
    }

    @Test
    fun decideLspAction_typstFileAndBinaryMissing_triggersDownload() {
        val action = decideLspAction(
            isUnitTestMode = false,
            isTypstFile = true,
            tinymistPath = null,
        )

        assertSame(LspStartAction.TriggerDownload, action)
    }

    @Test
    fun decideLspAction_unitTestModeShortCircuitsBeforeBinaryCheck() {
        // Even if tinymistPath is null (the "trigger download" trigger),
        // unit-test mode must short-circuit before TriggerDownload — otherwise
        // tests would fan out into the download service from environments
        // that have no network and no service initialized.
        val action = decideLspAction(
            isUnitTestMode = true,
            isTypstFile = true,
            tinymistPath = null,
        )

        assertSame(LspStartAction.Skip, action)
    }
}

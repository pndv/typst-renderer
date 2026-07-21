package com.github.pndv.typstrenderer.lsp

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the pure decision-table of [handleOpenedFileForExternalLsp] and the
 * file-claim partition shared by the two descriptor flavours.
 *
 * The handler itself touches IDE singletons (FileEditorManager, LspClientManager,
 * the download service); the branching lives in the pure [decideExternalLspAction]
 * and the claim predicates, same fixture-free pattern as
 * [TinymistLspServerSupportProviderTest].
 */
class TypstExternalFileLspStarterTest {

    @Test
    fun decideExternalLspAction_unitTestMode_skipsRegardlessOfEverything() {
        val action = decideExternalLspAction(
            isUnitTestMode = true,
            isTypstFile = true,
            isInContent = false,
            hasParentDir = true,
            tinymistPath = "/usr/local/bin/tinymist",
        )

        assertSame(LspStartAction.Skip, action)
    }

    @Test
    fun decideExternalLspAction_notTypstFile_skips() {
        val action = decideExternalLspAction(
            isUnitTestMode = false,
            isTypstFile = false,
            isInContent = false,
            hasParentDir = true,
            tinymistPath = "/usr/local/bin/tinymist",
        )

        assertSame(
            "Non-Typst files must never trigger an external LSP start",
            LspStartAction.Skip, action,
        )
    }

    @Test
    fun decideExternalLspAction_inContentFile_skips() { // In-content files are the platform's job: it calls the provider's
        // fileOpened for them, and handling them here as well would start a
        // second, differently-rooted client for the same file.
        val action = decideExternalLspAction(
            isUnitTestMode = false,
            isTypstFile = true,
            isInContent = true,
            hasParentDir = true,
            tinymistPath = "/usr/local/bin/tinymist",
        )

        assertSame(LspStartAction.Skip, action)
    }

    @Test
    fun decideExternalLspAction_noParentDir_skips() { // Without a parent directory there is nothing to root the client at.
        val action = decideExternalLspAction(
            isUnitTestMode = false,
            isTypstFile = true,
            isInContent = false,
            hasParentDir = false,
            tinymistPath = "/usr/local/bin/tinymist",
        )

        assertSame(LspStartAction.Skip, action)
    }

    @Test
    fun decideExternalLspAction_externalFileAndBinaryResolved_startsServer() {
        val path = "/usr/local/bin/tinymist"

        val action = decideExternalLspAction(
            isUnitTestMode = false,
            isTypstFile = true,
            isInContent = false,
            hasParentDir = true,
            tinymistPath = path,
        )

        assertTrue("Expected StartServer", action is LspStartAction.StartServer)
        assertEquals(path, (action as LspStartAction.StartServer).tinymistPath)
    }

    @Test
    fun decideExternalLspAction_externalFileAndBinaryMissing_triggersDownload() {
        val action = decideExternalLspAction(
            isUnitTestMode = false,
            isTypstFile = true,
            isInContent = false,
            hasParentDir = true,
            tinymistPath = null,
        )

        assertSame(LspStartAction.TriggerDownload, action)
    }

    @Test
    fun decideExternalLspAction_unitTestModeShortCircuitsBeforeDownload() {
        val action = decideExternalLspAction(
            isUnitTestMode = true,
            isTypstFile = true,
            isInContent = false,
            hasParentDir = true,
            tinymistPath = null,
        )

        assertSame(LspStartAction.Skip, action)
    }

    // ---- file-claim partition between the project-wide and external clients ----

    @Test
    fun fileClaims_partitionIsDisjoint() { // Whatever the content-membership of a .typ file, exactly one client
        // claims it — never both. Duplicate claims mean duplicate diagnostics
        // and exports routed to a client rooted elsewhere.
        for (isInContent in listOf(true, false)) {
            val project = projectClientClaims(isTypstFile = true, isInContent = isInContent)
            val external = externalClientClaims(isTypstFile = true, isInContent = isInContent, isUnderRoot = true)
            assertFalse(
                "project and external clients must never both claim a file (isInContent=$isInContent)",
                project && external,
            )
            assertTrue(
                "some client must claim a .typ file under an external root (isInContent=$isInContent)",
                project || external,
            )
        }
    }

    @Test
    fun projectClientClaims_onlyInContentTypstFiles() {
        assertTrue(projectClientClaims(isTypstFile = true, isInContent = true))
        assertFalse(projectClientClaims(isTypstFile = true, isInContent = false))
        assertFalse(projectClientClaims(isTypstFile = false, isInContent = true))
    }

    @Test
    fun externalClientClaims_onlyOutOfContentTypstFilesUnderItsRoot() {
        assertTrue(externalClientClaims(isTypstFile = true, isInContent = false, isUnderRoot = true))
        assertFalse(
            "files outside the client's folder root belong to some other client",
            externalClientClaims(isTypstFile = true, isInContent = false, isUnderRoot = false),
        )
        assertFalse(
            "in-content files belong to the project-wide client even under the external root",
            externalClientClaims(isTypstFile = true, isInContent = true, isUnderRoot = true),
        )
        assertFalse(externalClientClaims(isTypstFile = false, isInContent = false, isUnderRoot = true))
    }
}

package com.github.pndv.typstrenderer.editor

import com.github.pndv.typstrenderer.lsp.ExportPdfResult
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path

/**
 * Pure-function tests for the export pipeline's decision logic.
 *
 * The pipeline itself ([TypstFilePreviewer.exportWhenReady], [TypstFilePreviewer.handleUnavailable],
 * [TypstFilePreviewer.reloadPdf]) is welded to a JCEF browser, a [com.intellij.openapi.project.Project]
 * and a background scheduler — none of which a unit test can stand up (JCEF is unavailable headless,
 * so the editor never even constructs a browser). The branching that actually matters — when to poll
 * vs export vs give up, when a vanished server reverts to polling, and whether a reload can hot-swap
 * in place — is captured in the pure [decideExportReadiness], [decideUnavailableAction] and
 * [isViewerPage] functions and is what we cover here, mirroring
 * [com.github.pndv.typstrenderer.lsp.TinymistLspServerSupportProviderTest].
 */
class TypstFilePreviewerDecisionsTest {

    // ---- decideExportReadiness: the cold-start readiness poll ----

    @Test
    fun `readiness exports immediately once the server is up, ignoring poll count`() { // A ready server wins regardless of how many polls have elapsed — even past the budget,
        // so a server that comes up on the very last tick still exports rather than erroring.
        assertSame(ExportReadiness.Export, decideExportReadiness(serverReady = true, pollCount = 0, maxPolls = 10))
        assertSame(ExportReadiness.Export, decideExportReadiness(serverReady = true, pollCount = 99, maxPolls = 10))
    }

    @Test
    fun `readiness polls again with an incremented count while under budget`() {
        val decision = decideExportReadiness(serverReady = false, pollCount = 0, maxPolls = 10)
        assertTrue(decision is ExportReadiness.Poll)
        assertEquals(1, (decision as ExportReadiness.Poll).nextPollCount)
    }

    @Test
    fun `readiness keeps polling right up to the last attempt within budget`() { // pollCount 9 with maxPolls 10 is still < budget: one final poll (to 10) is scheduled.
        val decision = decideExportReadiness(serverReady = false, pollCount = 9, maxPolls = 10)
        assertTrue(decision is ExportReadiness.Poll)
        assertEquals(10, (decision as ExportReadiness.Poll).nextPollCount)
    }

    @Test
    fun `readiness gives up once the poll budget is exhausted`() { // This is the boundary the old field-vs-parameter bug broke: at pollCount == maxPolls the
        // loop MUST terminate with GiveUp, not schedule another poll forever.
        assertSame(ExportReadiness.GiveUp, decideExportReadiness(serverReady = false, pollCount = 10, maxPolls = 10))
        assertSame(ExportReadiness.GiveUp, decideExportReadiness(serverReady = false, pollCount = 11, maxPolls = 10))
    }

    // ---- decideUnavailableAction: post-Unavailable dispatch + transient retry ----

    @Test
    fun `unavailable with a vanished server reverts to polling, not a retry`() { // A server that went away (settings-change restart, crash) must NOT consume the short
        // transient-retry budget — it drops back to the readiness poll regardless of attempt.
        assertSame(
            UnavailableAction.PollForServer,
            decideUnavailableAction(serverReady = false, attempt = 0, maxRetries = 5),
        )
        assertSame(
            UnavailableAction.PollForServer,
            decideUnavailableAction(serverReady = false, attempt = 99, maxRetries = 5),
        )
    }

    @Test
    fun `unavailable with a live server retries with an incremented attempt`() {
        val decision = decideUnavailableAction(serverReady = true, attempt = 0, maxRetries = 5)
        assertTrue(decision is UnavailableAction.Retry)
        assertEquals(1, (decision as UnavailableAction.Retry).nextAttempt)
    }

    @Test
    fun `unavailable retries up to the last attempt within budget`() {
        val decision = decideUnavailableAction(serverReady = true, attempt = 4, maxRetries = 5)
        assertTrue(decision is UnavailableAction.Retry)
        assertEquals(5, (decision as UnavailableAction.Retry).nextAttempt)
    }

    @Test
    fun `unavailable gives up once the retry budget is exhausted`() {
        assertSame(
            UnavailableAction.GiveUp,
            decideUnavailableAction(serverReady = true, attempt = 5, maxRetries = 5),
        )
        assertSame(
            UnavailableAction.GiveUp,
            decideUnavailableAction(serverReady = true, attempt = 6, maxRetries = 5),
        )
    }

    // ---- isViewerPage: the hot-swap-vs-full-navigation decision ----

    // The real viewer base URL embeds a runtime port, but isViewerPage takes it as a parameter,
    // so we test the prefix relationship against a representative base without booting the server.
    private val viewerBase = "http://localhost:63342/typst-renderer-7f3a9c12/viewer/web/viewer.html"

    @Test
    fun `viewer page is recognised both bare and with a file query`() { // After a first load the browser sits on viewerUrl()?file=<encoded> — still a viewer page,
        // so reloads hot-swap in place.
        assertTrue(isViewerPage(viewerBase, viewerBase))
        assertTrue(isViewerPage("$viewerBase?file=http%3A%2F%2Flocalhost%2Fpdf%2Fabc", viewerBase))
    }

    @Test
    fun `a splash or error page is not a viewer page`() { // loadHTML(...) puts the browser on a synthetic about:blank-style URL with no bridge —
        // a reload here must fully navigate, never attempt a hot-swap.
        assertFalse(isViewerPage("about:blank", viewerBase))
        assertFalse(isViewerPage("data:text/html;charset=utf-8,<html>...</html>", viewerBase))
    }

    @Test
    fun `the bare pdf endpoint is not a viewer page`() { // The /pdf/<id> URL serves raw PDF bytes, not the viewer shell that carries the bridge.
        val pdfUrl = "http://localhost:63342/typst-renderer-7f3a9c12/pdf/abc?v=123"
        assertFalse(isViewerPage(pdfUrl, viewerBase))
    }

    @Test
    fun `a null current url is not a viewer page`() { // CefBrowser.getURL() can be null before the first navigation commits — treat as "not the
        // viewer" so the first render takes the full-navigation branch.
        assertFalse(isViewerPage(null, viewerBase))
    }

    // ---- ExportPdfResult: the contract runExport's `when` switches on ----

    @Test
    fun `Exported carries the reported path`() {
        val path = Path.of("/tmp/out.pdf")
        val result: ExportPdfResult = ExportPdfResult.Exported(path)
        assertEquals(path, (result as ExportPdfResult.Exported).pdf)
    }

    @Test
    fun `Failed carries the diagnostic detail`() {
        val detail = "error: label `<x>` occurs twice"
        val result: ExportPdfResult = ExportPdfResult.Failed(detail)
        assertEquals(detail, (result as ExportPdfResult.Failed).detail)
    }

    @Test
    fun `Unavailable is a singleton sentinel`() { // runExport matches it by identity (object), and two Exported results with different paths
        // must remain distinct — guards against anyone turning these into equal data shapes.
        assertSame(ExportPdfResult.Unavailable, ExportPdfResult.Unavailable)
        assertNotEquals(
            ExportPdfResult.Exported(Path.of("/a.pdf")),
            ExportPdfResult.Exported(Path.of("/b.pdf")),
        )
    }
}

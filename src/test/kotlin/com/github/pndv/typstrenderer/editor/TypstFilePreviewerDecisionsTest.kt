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

    // ---- backoffDelayMs: the cold-start readiness poll's exponential backoff ----

    @Test
    fun `backoff starts at the initial delay for the first poll`() { // nextPollCount is 1-based; the first scheduled poll waits the initial delay, not double it.
        assertEquals(250L, backoffDelayMs(pollCount = 1, initialMs = 250L, maxMs = 15_000L))
    }

    @Test
    fun `backoff doubles each poll until it reaches the cap`() {
        assertEquals(500L, backoffDelayMs(pollCount = 2, initialMs = 250L, maxMs = 15_000L))
        assertEquals(1_000L, backoffDelayMs(pollCount = 3, initialMs = 250L, maxMs = 15_000L))
        assertEquals(2_000L, backoffDelayMs(pollCount = 4, initialMs = 250L, maxMs = 15_000L))
        assertEquals(8_000L, backoffDelayMs(pollCount = 6, initialMs = 250L, maxMs = 15_000L))
    }

    @Test
    fun `backoff is clamped to the cap once doubling would exceed it`() { // 250 << 6 = 16000 > 15000, so poll 7 onward sits at the ceiling rather than growing.
        assertEquals(15_000L, backoffDelayMs(pollCount = 7, initialMs = 250L, maxMs = 15_000L))
        assertEquals(15_000L, backoffDelayMs(pollCount = 50, initialMs = 250L, maxMs = 15_000L))
    }

    @Test
    fun `backoff never overflows for large poll counts`() { // The shift is bounded, so even an absurd count returns the cap rather than a wrapped negative.
        assertEquals(15_000L, backoffDelayMs(pollCount = Int.MAX_VALUE, initialMs = 250L, maxMs = 15_000L))
    }

    @Test
    fun `backoff clamps non-positive poll counts to the initial delay`() {
        assertEquals(250L, backoffDelayMs(pollCount = 0, initialMs = 250L, maxMs = 15_000L))
        assertEquals(250L, backoffDelayMs(pollCount = -5, initialMs = 250L, maxMs = 15_000L))
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

    // ---- live-preview navigation intent ----

    @Test
    fun `a pane not in live mode never re-asserts`() { // desiredUrl is null outside live mode; re-asserting there would fight the PDF viewer
        // for control of the browser.
        assertFalse(shouldReassertLiveUrl("http://127.0.0.1:60344/", null))
        assertFalse(shouldReassertLiveUrl(null, null))
        assertFalse(shouldReassertLiveUrl("http://localhost:63342/typst-renderer/viewer/web/viewer.html", null))
    }

    @Test
    fun `the live page committing is not a lost race`() {
        val wanted =
            "http://127.0.0.1:60344" // The browser reports the committed URL with a trailing slash the request lacked, so
        // this must be a prefix match — equality would re-assert forever on every success.
        assertFalse(shouldReassertLiveUrl("http://127.0.0.1:60344/", wanted))
        assertFalse(shouldReassertLiveUrl(wanted, wanted))
    }

    @Test
    fun `the splash page committing over the live page is a lost race`() { // The exact failure seen in the field: the browser's own deferred first load (a
        // JBCefBrowser.loadHTML page, which reports as file:///jbcefbrowser/...) commits after
        // our loadURL and aborts it, stranding the pane on "Compiling…" forever.
        val wanted = "http://127.0.0.1:60344"
        assertTrue(shouldReassertLiveUrl("file:///jbcefbrowser/488045838#url=about:blank", wanted))
        assertTrue(shouldReassertLiveUrl("about:blank", wanted))
        assertTrue(shouldReassertLiveUrl(null, wanted))
        assertTrue(shouldReassertLiveUrl("", wanted))
    }

    @Test
    fun `a different preview server's page is a lost race`() { // A stale task's page must not be mistaken for the current one — ports differ, and a
        // prefix match on the host alone would accept the wrong server.
        assertTrue(shouldReassertLiveUrl("http://127.0.0.1:60999/", "http://127.0.0.1:60344"))
    }

    @Test
    fun `the selected live pane with a running task syncs its caret`() {
        assertTrue(
            shouldSyncPreviewToCaret(
                mode = TypstPreviewMode.LIVE,
                followCursor = true,
                editorIsSelected = true,
                hasLiveTask = true,
            )
        )
    }

    @Test
    fun `a background tab never scrolls the preview`() { // The scroll is addressed to the task, so it reaches every pane sharing it. A tab the
        // user is not looking at sending one would yank the pane they are reading to a position
        // in a file they did not open — the whole reason for the selected-editor condition.
        assertFalse(
            shouldSyncPreviewToCaret(
                mode = TypstPreviewMode.LIVE,
                followCursor = true,
                editorIsSelected = false,
                hasLiveTask = true,
            )
        )
    }

    @Test
    fun `the PDF renderer and the disabled setting both suppress the sync`() { // The PDF pane keeps its own scroll through the PDF.js bridge and has no task to address.
        assertFalse(
            shouldSyncPreviewToCaret(
                mode = TypstPreviewMode.PDF,
                followCursor = true,
                editorIsSelected = true,
                hasLiveTask = true,
            )
        )
        assertFalse(
            shouldSyncPreviewToCaret(
                mode = TypstPreviewMode.LIVE,
                followCursor = false,
                editorIsSelected = true,
                hasLiveTask = true,
            )
        )
    }

    @Test
    fun `a pane holding no task does not try to scroll one`() { // A caret move must never be the thing that starts a preview: the pane is mid-cold-start
        // or has fallen back, and there is no task id to address.
        assertFalse(
            shouldSyncPreviewToCaret(
                mode = TypstPreviewMode.LIVE,
                followCursor = true,
                editorIsSelected = true,
                hasLiveTask = false,
            )
        )
    }
}

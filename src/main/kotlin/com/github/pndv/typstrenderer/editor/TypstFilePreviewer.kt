package com.github.pndv.typstrenderer.editor

import com.github.pndv.typstrenderer.Common.printToConsole
import com.github.pndv.typstrenderer.TYPST_OUTPUT_TOOL_WINDOW_ID
import com.github.pndv.typstrenderer.TypstBundle
import com.github.pndv.typstrenderer.lsp.ExportPdfResult
import com.github.pndv.typstrenderer.lsp.TinymistCommands
import com.github.pndv.typstrenderer.settings.TypstSettingsState
import com.github.pndv.typstrenderer.theme.TypstThemeListener
import com.github.pndv.typstrenderer.theme.TypstThemeService
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.diagnostic.trace
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.ColorUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import org.cef.CefSettings
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import java.beans.PropertyChangeListener
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.JComponent
import javax.swing.SwingConstants

/**
 * What the cold-start readiness loop ([TypstFilePreviewer.exportWhenReady]) should do next,
 * given whether the tinymist LSP is up and how many times we have already polled. Extracted
 * as a pure decision so the loop's termination — the bug-prone part — can be tested without a
 * real LSP server or JCEF browser, mirroring [com.github.pndv.typstrenderer.lsp.decideLspAction].
 */
internal sealed interface ExportReadiness {
    /** The server is up — run the export now. */
    data object Export : ExportReadiness

    /** The server is not up yet, and budget remains — poll again at [nextPollCount]. */
    data class Poll(val nextPollCount: Int) : ExportReadiness

    /** The poll budget is exhausted — surface a hard "LSP unavailable" error. */
    data object GiveUp : ExportReadiness
}

internal fun decideExportReadiness(serverReady: Boolean, pollCount: Int, maxPolls: Int): ExportReadiness = when {
    serverReady -> ExportReadiness.Export
    pollCount < maxPolls -> ExportReadiness.Poll(pollCount + 1)
    else -> ExportReadiness.GiveUp
}

/**
 * Exponential backoff for the cold-start readiness poll: the delay before the [pollCount]-th
 * readiness check, in milliseconds. Starts at [initialMs] for the first poll and doubles each
 * step, capped at [maxMs] — so a freshly spawned tinymist (typically up within 1–3s) yields a
 * near-instant first render, while a server that never comes up still backs off to an inexpensive
 * steady-state poll rather than hammering the readiness check.
 *
 * [pollCount] is 1-based (the count carried by [ExportReadiness.Poll.nextPollCount]); values
 * below 1 clamp to the initial delay. The shift is bounded so the doubling cannot overflow.
 */
internal fun backoffDelayMs(pollCount: Int, initialMs: Long, maxMs: Long): Long {
    val shift = (pollCount - 1).coerceIn(0, 20)
    return (initialMs shl shift).coerceAtMost(maxMs)
}

/**
 * What to do after an export round-trip came back [com.github.pndv.typstrenderer.lsp.ExportPdfResult.Unavailable]
 * (see [TypstFilePreviewer.handleUnavailable]), given whether the server is still up and how
 * many transient retries we have already spent. A vanished server drops back to the readiness
 * poll rather than burning the short retry budget.
 */
internal sealed interface UnavailableAction {
    /** The server went away — drop back to the readiness poll. */
    data object PollForServer : UnavailableAction

    /** The server is up but the round-trip bounced — retry the export at [nextAttempt]. */
    data class Retry(val nextAttempt: Int) : UnavailableAction

    /** The transient-retry budget is exhausted — surface a hard error. */
    data object GiveUp : UnavailableAction
}

internal fun decideUnavailableAction(serverReady: Boolean, attempt: Int, maxRetries: Int): UnavailableAction = when {
    !serverReady -> UnavailableAction.PollForServer
    attempt < maxRetries -> UnavailableAction.Retry(attempt + 1)
    else -> UnavailableAction.GiveUp
}

/**
 * Whether a reload can hot-swap the PDF in place or must fully navigate to the viewer. The
 * `__typstOpenPdf` bridge exists only on the PDF.js viewer page, so a hot-swap is safe only
 * while the browser is actually on that page (optionally with a `?file=` query) — never on a
 * splash or error page. Derived from the browser's live URL in [TypstFilePreviewer.reloadPdf]
 * rather than a cached flag, so a navigation away from the viewer can never leave it stale.
 */
internal fun isViewerPage(currentUrl: String?, viewerUrl: String): Boolean =
    currentUrl != null && currentUrl.startsWith(viewerUrl)

/**
 * A file editor that shows a live PDF preview of a Typst file.
 *
 * Renders the compiled PDF in a JCEF (embedded Chromium) browser panel. The PDF is produced
 * by tinymist via the LSP `tinymist.exportPdf` command (see [TinymistCommands]) — there is no
 * `typst watch` subprocess. The preview re-exports (debounced) whenever the previewed file
 * itself is saved, and reloads the panel from the path tinymist reports back.
 */
class TypstFilePreviewer(
    private val project: Project, private val file: VirtualFile
) : UserDataHolderBase(), FileEditor {

    private val log = logger<TypstFilePreviewer>()

    private val jcefSupported = JBCefApp.isSupported()

    private val browser: JBCefBrowser? = if (jcefSupported) JBCefBrowser() else null
    private val fallbackLabel = JBLabel(TypstBundle.message("previewer.jcef.unsupported"), SwingConstants.CENTER)

    /**
     * The most recently exported PDF, as reported by tinymist. `null` until the first
     * successful export. Read by the [PdfjsPreviewerRegistry] registration and [reloadPdf];
     * written on the export thread, hence @Volatile.
     */
    private val reloadExecutor = AppExecutorUtil.createBoundedScheduledExecutorService("TypstPdfReload", 1)

    @Volatile
    private var outputPdf: File? = null

    @Volatile
    private var reloadJob: ScheduledFuture<*>? = null

    /** Pending export-pipeline task: a debounced save re-export, a readiness poll, or a
     *  transient retry. Newer triggers cancel and replace it, so one chain is live at a time. */
    @Volatile
    private var exportJob: ScheduledFuture<*>? = null

    private val colourScheme get() = if (ColorUtil.isDark(UIUtil.getPanelBackground())) "dark" else "light"
    internal fun getBgColour() = ColorUtil.toHtmlColor(UIUtil.getPanelBackground())
    internal fun getFgColour() = ColorUtil.toHtmlColor(UIUtil.getLabelForeground())
    internal fun getFgSubColour() = ColorUtil.toHtmlColor(UIUtil.getContextHelpForeground())
    internal fun getErrorFgColour() = ColorUtil.toHtmlColor(NamedColorUtil.getErrorForeground())
    internal fun getFontFamilyString() = "'${UIUtil.getLabelFont().family}', sans-serif"
    internal fun getLabelFontSize() = UIUtil.getLabelFont().size

    /**
     * Last viewport reported by the in-page PDF.js bridge (see `pdfjs-bridge.js`).
     * Mirrored to [FileEditorState] when the "remember across restart" setting is on,
     * and pushed back to JS before each reload so the document re-opens at the same spot.
     */
    @Volatile
    private var lastViewport: PdfViewportState? = null

    /** JS → Kotlin bridge used by `pdfjs-bridge.js` to report viewport changes. */
    private val viewportQuery: JBCefJSQuery? =
        browser?.let { JBCefJSQuery.create(it as com.intellij.ui.jcef.JBCefBrowserBase) }

    /** Loaded lazily once — the bridge JS with the JSQuery-inject snippet substituted in. */
    private val bridgeJs: String by lazy { loadBridgeJs() }

    /** Stable per-previewer ID used in HTTP URLs to route /pdf/<id> and /bridge/<id> requests. */
    private val previewerId: String = java.util.UUID.randomUUID().toString()

    init {
        if (jcefSupported) {
            browser?.let { Disposer.register(this, it) }
            viewportQuery?.let { Disposer.register(this, it) }
            installViewportBridge()
            installRequestHandler()
            installLoadEndHandlers()
            listenForThemeChanges()
            listenForFileChanges()

            // Show a placeholder, then kick off the first export so the panel
            // populates without waiting for the user to save.
            browser?.loadHTML(waitingHtml())
            scheduleExportWhenReady()
        }
    }

    /** Wires the JS-side viewport reports into [lastViewport]. */
    private fun installViewportBridge() {
        viewportQuery?.addHandler { json ->
            PdfViewportState.fromJson(json)?.let { lastViewport = it }
            null
        }
    }

    /**
     * Registers this previewer with [PdfjsPreviewerRegistry] so the built-in HTTP
     * server can resolve `/pdf/<id>` and `/bridge/<id>` requests to this editor's
     * compiled PDF and bridge JS. Unregistered in [dispose].
     *
     * We use IntelliJ's Built-In Netty server (the same one used by the platform
     * Markdown plugin and the third-party intellij-pdf-viewer) instead of a
     * `CefSchemeHandlerFactory` or per-browser `CefRequestHandler`. In remote
     * JCEF (the default in 2024.3+ IDEs) sub-resource fetches bypass per-browser
     * handlers; serving over real HTTP works in both in-process and remote modes.
     */
    private fun installRequestHandler() {
        PdfjsPreviewerRegistry.register(
            PdfjsPreviewerRegistration(
                id = previewerId,
                currentPdf = { outputPdf?.takeIf { it.isFile && it.length() > 0 } },
                bridgeJs = { bridgeJs },
            )
        )
    }

    /** Reads `pdfjs-bridge.js` from the classpath and substitutes the JSQuery-inject placeholder. */
    private fun loadBridgeJs(): String {
        val raw = javaClass.getResourceAsStream("/pdfjs-bridge.js")?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: return ""
        val injected = viewportQuery?.inject("payload") ?: ""
        return raw.replace("/*__REPORT_CALL__*/", "$injected;")
    }

    /**
     * Installs the `onLoadEnd` handler. It has two jobs:
     *  1. Apply dark colour-scheme so the PDF.js viewer blends into dark themes.
     *  2. Inject the bridge JS after the viewer page finishes loading so reloads can
     *     hot-swap via `__typstOpenPdf`. Whether to hot-swap is derived from the
     *     browser's current URL in [reloadPdf] — only viewer pages carry the bridge.
     */
    private fun installLoadEndHandlers() {
        log.debug("Installing onLoadEnd handlers")
        val cefBrowser = browser?.cefBrowser ?: return
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(
                b: org.cef.browser.CefBrowser, frame: org.cef.browser.CefFrame, httpStatusCode: Int
            ) {
                val url = frame.url.orEmpty()
                log.info("[pdfjs] onLoadEnd status=$httpStatusCode url=$url isMain=${frame.isMain}")
                b.executeJavaScript(
                    "document.documentElement.style.colorScheme='$colourScheme';", url, 0
                )

                if (frame.isMain && url.startsWith(PdfjsEndpoints.viewerUrl())) {
                    b.executeJavaScript(
                        bridgeJs, url, 0
                    )

                    // If a viewport was persisted from a previous session, push it to
                    // the bridge so `pagesloaded` restores it.
                    lastViewport?.let { pushPendingRestore(it) }
                }
            }

            // Surfaces network-level failures (DNS, refused, aborted, blocked, etc.)
            // that don't show up as a 404 from our HttpRequestHandler. Without this,
            // a request that never reaches our server (e.g. blocked by JCEF policy)
            // is invisible in idea.log.
            override fun onLoadError(
                b: org.cef.browser.CefBrowser?,
                frame: org.cef.browser.CefFrame?,
                errorCode: org.cef.handler.CefLoadHandler.ErrorCode?,
                errorText: String?,
                failedUrl: String?
            ) {
                log.warn("[pdfjs] onLoadError code=$errorCode text=$errorText url=$failedUrl isMain=${frame?.isMain}")
            }
        }, cefBrowser)

        // Forwards in-page console.log/info/warn/error to idea.log. JCEF doesn't
        // surface JS console output anywhere by default — without this hook the
        // page can be silently throwing exceptions and we'd have no idea.
        browser.jbCefClient.addDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onConsoleMessage(
                b: org.cef.browser.CefBrowser?,
                level: CefSettings.LogSeverity?,
                message: String?,
                source: String?,
                line: Int
            ): Boolean {
                val tag = when (level) {
                    CefSettings.LogSeverity.LOGSEVERITY_ERROR, CefSettings.LogSeverity.LOGSEVERITY_FATAL -> "ERROR"
                    CefSettings.LogSeverity.LOGSEVERITY_WARNING -> "WARN"
                    else -> "INFO"
                }
                val src = source?.substringAfterLast('/') ?: "?"
                when (tag) {
                    "ERROR" -> log.warn("[js:$tag] $src:$line $message")
                    "WARN" -> log.info("[js:$tag] $src:$line $message")
                    else -> log.info("[js] $src:$line $message")
                }
                return false
            }
        }, cefBrowser)
    }

    /** Sends a viewport restore target to the in-page bridge. No-op if the viewer isn't loaded yet. */
    private fun pushPendingRestore(v: PdfViewportState) {
        val cef = browser?.cefBrowser ?: return
        val json = """{"page":${v.page},"yOffset":${v.yOffset}}"""
        val escaped = json.replace("\\", "\\\\").replace("'", "\\'")
        cef.executeJavaScript(
            "window.__typstSetPendingRestore && window.__typstSetPendingRestore('$escaped');", cef.url, 0
        )
    }

    /** Re-applies theme colours to the preview in-place whenever the IDE theme changes (no reload). */
    private fun listenForThemeChanges() { // Instantiate the app service so its init subscribes to LaF/editor events and republishes them on TOPIC.
        TypstThemeService.getInstance()
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(TypstThemeService.TOPIC, TypstThemeListener { _ ->
                ApplicationManager.getApplication().invokeLater {
                    val cef = browser?.cefBrowser ?: return@invokeLater
                    log.debug("[theme] re-applying preview colours (scheme=$colourScheme)")

                    // Theme changes only tweak CSS in-page — no reload, so scroll is preserved.
                    cef.executeJavaScript(
                        "document.documentElement.style.colorScheme='$colourScheme';", cef.url, 0
                    ) // Recolour a splash/error page if one is showing. The id lookup is a no-op on
                    // the PDF.js viewer (no such element), so the PDF itself is left untouched.
                    val recolourJs = """
                        (function() {
                            var body = document.getElementById('typst-splash');
                            if (!body) return;
                            body.style.background = '${getBgColour()}';
                            body.style.color =
                                body.dataset.kind === 'error' ? '${getErrorFgColour()}' : '${getFgColour()}';
                            var sub = document.getElementById('typst-splash-sub');
                            if (sub) sub.style.color = '${getFgSubColour()}';
                        })();
                    """.trimIndent()
                    cef.executeJavaScript(recolourJs, cef.url, 0)
                }
            })
    }

    // ---- FileEditor interface ----

    override fun getComponent(): JComponent = browser?.component ?: fallbackLabel
    override fun getPreferredFocusedComponent(): JComponent? = browser?.component
    override fun getName(): String = TypstBundle.message("previewer.window.name")
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = file.isValid

    override fun getState(level: FileEditorStateLevel): FileEditorState {
        if (!TypstSettingsState.getInstance().rememberPreviewScrollAcrossRestart) {
            return FileEditorState.INSTANCE
        }
        val v = lastViewport ?: return FileEditorState.INSTANCE
        return PdfViewportFileEditorState.from(v)
    }

    override fun setState(state: FileEditorState) {
        if (!TypstSettingsState.getInstance().rememberPreviewScrollAcrossRestart) return
        (state as? PdfViewportFileEditorState)?.toViewport()?.let { lastViewport = it }
    }

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun getFile(): VirtualFile = file

    override fun dispose() {
        PdfjsPreviewerRegistry.unregister(previewerId)
        exportJob?.cancel(false)
        reloadJob?.cancel(false)
        reloadExecutor.shutdownNow() // The exported PDF is a real project artefact (it lives in the configured export
        // directory, same as a manual Compile) — leave it on disk.
    }

    // ---- PDF export via tinymist LSP ----

    /** Polls for the tinymist LSP to be Running, then exports once. Before the first render
     *  the waiting page stays up while polling. Polls the inexpensive readiness check — never
     *  the export itself — so a slow cold start no longer yields false "no PDF" errors. */
    private fun scheduleExportWhenReady() {
        exportJob?.cancel(false)
        exportJob = reloadExecutor.schedule({ exportWhenReady(pollCount = 0) }, 0, TimeUnit.MILLISECONDS)  // check now
    }

    private fun exportWhenReady(pollCount: Int) {
        if (project.isDisposed || !file.isValid) return
        val isReady = decideExportReadiness(
            TinymistCommands.isServerReady(
                project, Path.of(file.path)
            ), pollCount, MAX_SERVER_POLLS
        )
        when (isReady) {
            ExportReadiness.Export -> runExport(attempt = 0)

            is ExportReadiness.Poll -> {
                val delayMs = backoffDelayMs(isReady.nextPollCount, INITIAL_SERVER_POLL_MS, MAX_SERVER_POLL_MS)
                log.debug { "[preview] tinymist not ready; poll ${isReady.nextPollCount} of $MAX_SERVER_POLLS in ${delayMs}ms for ${file.path}" }
                showWaitingPageIfNoRender()
                exportJob = reloadExecutor.schedule(
                    { exportWhenReady(isReady.nextPollCount) },
                    delayMs,
                    TimeUnit.MILLISECONDS,
                )
            }

            ExportReadiness.GiveUp -> showPreviewError(
                TypstBundle.message(
                    "console.compile.failed",
                    TypstBundle.message("console.compile.failed.lspUnavailable"),
                )
            )
        }
    }

    /**
     * Debounced re-export. Coalesces a burst of saves (e.g. several included chapters saved
     * together, or the editor's autosave firing rapidly) into a single export. Scheduled on
     * [reloadExecutor], whose single worker thread satisfies
     * [TinymistCommands.exportPdf]'s @RequiresBackgroundThread contract.
     */
    private fun scheduleExport() {
        exportJob?.cancel(false)
        exportJob = reloadExecutor.schedule({ runExport(attempt = 0) }, EXPORT_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * Exports the previewed file through tinymist and refreshes the panel.
     *
     * Tinymist owns the output destination (the `tinymist.outputPath` template, derived from
     * the per-project export-directory setting), so we neither pass nor compute a path — we use
     * the one it reports back in [ExportPdfResult.Exported]. A compile failure arrives as
     * [ExportPdfResult.Failed] carrying tinymist's formatted diagnostic (no stderr scraping);
     * a transport failure as [ExportPdfResult.Unavailable].
     */
    private fun runExport(attempt: Int) {
        try {
            if (project.isDisposed || !file.isValid) return

            log.debug { "[preview] exporting ${file.path} via tinymist" }
            val result = TinymistCommands.exportPdf(project, Path.of(file.path))
            log.debug { "[preview] export result for ${file.path}: $result" }

            when (result) {
                is ExportPdfResult.Exported -> {
                    outputPdf =
                        result.pdf.toFile() // tinymist writes the PDF outside the IDE's VFS; refresh so the VFS listener
                    // and any open editors observe the new bytes, then reload the panel.
                    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(result.pdf)
                    scheduleReloadPdf()
                }

                // tinymist rejected the document — show the generic error pane and route the
                // formatted diagnostic to the Typst Output console (the user's primary signal;
                // the error often lives in an #include-d chapter, not the focused file).
                is ExportPdfResult.Failed -> showPreviewError(
                    TypstBundle.message("console.compile.failed", result.detail)
                )

                // The round-trip never reached the server. handleUnavailable decides whether
                // the server vanished (back to the readiness poll) or merely bounced (short
                // transient retry) — see [decideUnavailableAction].
                ExportPdfResult.Unavailable -> handleUnavailable(attempt)
            }
        } catch (pce: ProcessCanceledException) {
            throw pce
        } catch (e: Exception) {
            val message = TypstBundle.message("console.export.error", file.name, e.message ?: "")
            showPreviewError(message)
        }
    }

    /**
     * Handles an [ExportPdfResult.Unavailable] round-trip. If the server vanished (restart
     * after a settings change, crash) we drop back to the readiness poll rather than burning
     * the short retry budget; if it is up but the round-trip merely bounced (e.g. mid-restart)
     * we retry a few times on a short delay before surfacing a hard error. Before the first
     * render the waiting page stays up while retrying; once a PDF is showing, retries happen
     * silently behind it.
     */
    private fun handleUnavailable(attempt: Int) {
        when (val decision = decideUnavailableAction(
            TinymistCommands.isServerReady(project, Path.of(file.path)), attempt, MAX_EXPORT_RETRIES
        )) {
            UnavailableAction.PollForServer -> scheduleExportWhenReady()

            is UnavailableAction.Retry -> {
                log.debug { "[preview] tinymist up but export bounced; retry ${decision.nextAttempt} of $MAX_EXPORT_RETRIES for ${file.path}" }
                showWaitingPageIfNoRender()
                exportJob?.cancel(false)
                exportJob = reloadExecutor.schedule(
                    { runExport(decision.nextAttempt) },
                    EXPORT_RETRY_MS,
                    TimeUnit.MILLISECONDS,
                )
            }

            UnavailableAction.GiveUp -> showPreviewError(
                TypstBundle.message(
                    "console.compile.failed",
                    TypstBundle.message("console.compile.failed.lspUnavailable"),
                )
            )
        }
    }

    /**
     * Shows the "Compiling…" splash, but only before the first successful render. Once a PDF
     * is on screen, transient polls and retries happen silently behind it: replacing the
     * loaded viewer with a splash would tear down the page that carries the `__typstOpenPdf`
     * bridge, stranding the next hot-swap.
     */
    private fun showWaitingPageIfNoRender() {
        if (outputPdf != null) return
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) browser?.loadHTML(waitingHtml())
        }
    }

    /**
     * Renders the generic compile-error pane and prints [consoleMessage] to the Typst Output
     * console. The pane text is an author-controlled bundle string; the uncontrolled diagnostic
     * detail goes only to the console, so no HTML escaping is needed here.
     */
    private fun showPreviewError(consoleMessage: String) {
        printToConsole(project, log, consoleMessage, ConsoleViewContentType.ERROR_OUTPUT)
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            ToolWindowManager.getInstance(project).getToolWindow(TYPST_OUTPUT_TOOL_WINDOW_ID)?.show()
            browser?.loadHTML(errorHtml(TypstBundle.message("previewer.error.compileFailed")))
        }
    }

    // ---- VFS listener: source saves trigger re-export, PDF writes trigger reload ----

    /**
     * One VFS subscription doing two jobs:
     *  - The previewed file itself was saved → schedule a re-export;
     *  - A write to our current output PDF → schedule a reload (also catches a PDF produced
     *    by a manual Compile of the same file).
     *
     * Cross-file refresh (a root document re-exporting when an #included chapter is edited
     * in another tab) is intentionally out of scope here — it arrives with the multi-file
     * "pin main" feature (Tier 1.2.2 Part C), keyed off the pinned main file rather than a
     * parsed dependency graph.
     */
    private fun listenForFileChanges() {
        val connection = project.messageBus.connect(this)
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                for (event in events) {
                    if (event !is VFileContentChangeEvent) continue
                    val path = event.path

                    // A write to our current output PDF → reload the panel. FileUtil.pathsEqual
                    // canonicalises separators and compares case-insensitively on Windows, so the
                    // VFS-style ('/'-separated) event path still matches a java.io.File path that
                    // uses '\' and a lowercase drive letter (as tinymist reports it).
                    if (FileUtil.pathsEqual(path, outputPdf?.path)) {
                        scheduleReloadPdf()
                        continue
                    }

                    // The previewed file itself was saved → re-export.
                    if (path == file.path) {
                        scheduleExport()
                    }
                }
            }
        })
    }

    // ---- Reload the PDF in the JCEF browser ----

    /** Debounce: coalesces rapid reload requests (e.g. "writing to" + "compiled" arriving together). */
    private fun scheduleReloadPdf() {
        if (reloadJob != null) {
            log.debug { "There is already a reload job for ${file.name}, cancelling pending reload job" }
            reloadJob?.cancel(false)
        }
        reloadJob = reloadExecutor.schedule(::reloadPdf, 300, TimeUnit.MILLISECONDS)
    }

    private fun reloadPdf() {
        if (!jcefSupported || browser == null) return
        val pdf = outputPdf ?: return // Capture the volatile property into local variable to get a snapshot
        if (!pdf.exists() || pdf.length() == 0L) return

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            // Cache-bust via query param so the browser re-fetches the PDF on each compile operation.
            val pdfUrl = PdfjsEndpoints.pdfUrl(previewerId, System.currentTimeMillis())

            // Hot-swap: ask PDF.js to load the new PDF in-place. The bridge snapshots the current viewport as
            // "pendingRestore" before swapping, so the scroll is preserved.
            val cef = browser.cefBrowser
            if (isViewerPage(cef.url, PdfjsEndpoints.viewerUrl())) {
                cef.executeJavaScript("window.__typstOpenPdf && window.__typstOpenPdf('$pdfUrl');", cef.url, 0)
                log.debug("[viewport] hot-swap PDF via bridge — url=$pdfUrl")
            } else { // First load: navigate to the PDF.js viewer with the compiled PDF as its ?file= arg.
                val encoded = java.net.URLEncoder.encode(pdfUrl, Charsets.UTF_8)
                val viewerUrl = "${PdfjsEndpoints.viewerUrl()}?file=$encoded"
                log.info("[viewport] loading PDF.js viewer — url=$viewerUrl")
                browser.loadURL(viewerUrl)
            }
        }
    }

    // ---- Utility HTML pages ----

    private fun waitingHtml(
        message: String = TypstBundle.message("previewer.waiting.compiling"),
        detail: String = TypstBundle.message("previewer.waiting.detail")
    ): String {
        val (bg, fg, fgSub) = Triple(getBgColour(), getFgColour(), getFgSubColour())
        val html = """
            <html>
            <body id="typst-splash" 
                  data-kind="waiting"
                  style="display:flex;align-items:center;justify-content:center;height:100vh;margin:0;
                         font-family:${getFontFamilyString()};
                         font-size:${getLabelFontSize()}px;
                         color:$fg;
                         background:$bg;">
                <div style="text-align:center;">
                    <p style="font-size:1.2em;">$message</p>
                    <p id="typst-splash-sub" style="font-size:0.9em;color:$fgSub;">$detail</p>
                </div>
            </body>
            </html>
        """.trimIndent()

        log.trace { "[waitingHtml]:\n$html" }

        return html
    }

    /**
     * Renders an error page in the preview pane. The [message] is treated as
     * already-safe HTML — bundle messages can include intentional tags like
     * `<br>` for line breaks. Callers passing user-supplied content (e.g. an
     * exception's `message`) are responsible for escaping it via
     * StringUtil.escapeXmlEntities (or other methods/libraries0 before substituting it
     * into the bundle template, so the bundle's tags survive while uncontrolled input is safe.
     */
    private fun errorHtml(message: String): String {
        val (bg, fgSub) = Pair(getBgColour(), getFgSubColour())
        val html = """
            <html>
                <body id="typst-splash" 
                      data-kind="error"
                      style="display:flex;align-items:center;justify-content:center;height:100vh;margin:0;
                             font-family:${getFontFamilyString()};
                             font-size:${getLabelFontSize()}px;
                             color:${getErrorFgColour()};
                             background:$bg;">
                    <div style="text-align:center;
                         max-width:500px;
                         padding:20px;">
                        <p style="font-size:1.2em;font-weight:bold;">${TypstBundle.message("previewer.error.title")}</p>
                        <p id="typst-splash-sub" style="font-size:0.9em;color:$fgSub;">$message</p>
                    </div>
                </body>
            </html>
        """.trimIndent()

        log.trace { "[errorHtml]:\n$html" }

        return html
    }

    private companion object {
        /** Debounce window for save-triggered re-exports. */
        const val EXPORT_DEBOUNCE_MS = 300L

        /** Delay between transient export retries while the server is running. */
        const val EXPORT_RETRY_MS = 600L

        /** Max transient export retries before surfacing a hard "LSP unavailable" error. */
        const val MAX_EXPORT_RETRIES = 5

        /** Delay before the first readiness re-check — kept short so a quick cold start renders almost immediately. */
        const val INITIAL_SERVER_POLL_MS = 250L

        /** Ceiling the exponential backoff settles at once the early polls are exhausted. */
        const val MAX_SERVER_POLL_MS = 15_000L

        /**
         * Give up after this many polls. With the 250ms→15s backoff above, the cumulative wait
         * before [ExportReadiness.GiveUp] is ~150s (~2.5 min) — matching the previous flat-15s
         * budget while making the first render far snappier.
         */
        const val MAX_SERVER_POLLS = 15
    }
}

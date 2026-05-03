package com.github.pndv.typstrenderer.editor

import com.github.pndv.typstrenderer.TYPST_OUTPUT_TOOL_WINDOW_ID
import com.github.pndv.typstrenderer.TypstBundle
import com.github.pndv.typstrenderer.lsp.TinymistManager
import com.github.pndv.typstrenderer.lsp.TypstDownloadService
import com.github.pndv.typstrenderer.settings.TypstSettingsState
import com.github.pndv.typstrenderer.theme.TypstThemeListener
import com.github.pndv.typstrenderer.theme.TypstThemeService
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.io.BaseOutputReader
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
 * A file editor that shows a live PDF preview of a Typst file.
 *
 * Runs `typst watch <input> <output.pdf>` in the background and renders
 * the output PDF in a JCEF (embedded Chromium) browser panel.
 * The preview auto-refreshes whenever typst recompiles the PDF.
 */
class TypstFilePreviewer(
    private val project: Project,
    private val file: VirtualFile
) : UserDataHolderBase(), FileEditor {

    private val log = logger<TypstFilePreviewer>()

    private val jcefSupported = JBCefApp.isSupported()

    private val browser: JBCefBrowser? = if (jcefSupported) JBCefBrowser() else null
    private val fallbackLabel = JBLabel(TypstBundle.message("previewer.jcef.unsupported"), SwingConstants.CENTER)

    private var processHandler: OSProcessHandler? = null
    private val outputPdf: File
    private val reloadExecutor = AppExecutorUtil.createBoundedScheduledExecutorService("TypstPdfReload", 1)
    private var reloadJob: ScheduledFuture<*>? = null

    private val isDark get() = EditorColorsManager.getInstance().isDarkEditor

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

    /** True once the PDF.js viewer page has finished its first load. Subsequent reloads hot-swap via JS. */
    @Volatile
    private var viewerLoaded: Boolean = false

    /** Stable per-previewer ID used in HTTP URLs to route /pdf/<id> and /bridge/<id> requests. */
    private val previewerId: String = java.util.UUID.randomUUID().toString()

    init {
        // Output PDF goes next to the source file in a temp dir to avoid polluting the project
        val tempDir = File(System.getProperty("java.io.tmpdir"), "typst-preview")
        tempDir.mkdirs()
        val baseName = file.nameWithoutExtension
        outputPdf = File(tempDir, "${baseName}_${file.path.hashCode()}.pdf")

        if (jcefSupported) {
            browser?.let { Disposer.register(this, it) }
            viewportQuery?.let { Disposer.register(this, it) }
            installViewportBridge()
            installRequestHandler()
            installLoadEndHandlers()
            listenForThemeChanges()
            startWatching()
            listenForPdfChanges()
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
                currentPdf = { if (outputPdf.isFile && outputPdf.length() > 0) outputPdf else null },
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
     *  2. Inject the bridge JS after the viewer page finishes loading, and mark the
     *     viewer as loaded so subsequent reloads can hot-swap via `__typstOpenPdf`.
     */
    private fun installLoadEndHandlers() {
        log.debug("Installing onLoadEnd handlers")
        val cefBrowser = browser?.cefBrowser ?: return
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(
                b: org.cef.browser.CefBrowser,
                frame: org.cef.browser.CefFrame,
                httpStatusCode: Int
            ) {
                val url = frame.url.orEmpty()
                log.info("[pdfjs] onLoadEnd status=$httpStatusCode url=$url isMain=${frame.isMain}")
                if (isDark) {
                    b.executeJavaScript(
                        "document.documentElement.style.colorScheme='dark';",
                        url, 0
                    )
                }
                if (frame.isMain && url.startsWith(PdfjsEndpoints.viewerUrl())) {
                    b.executeJavaScript(bridgeJs, url, 0)
                    // If a viewport was persisted from a previous session, push it to
                    // the bridge so `pagesloaded` restores it.
                    lastViewport?.let { pushPendingRestore(it) }
                    viewerLoaded = true
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
                    CefSettings.LogSeverity.LOGSEVERITY_ERROR,
                    CefSettings.LogSeverity.LOGSEVERITY_FATAL -> "ERROR"
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
            "window.__typstSetPendingRestore && window.__typstSetPendingRestore('$escaped');",
            cef.url, 0
        )
    }

    /** Reloads the browser content with updated theme colours whenever the IDE theme changes. */
    private fun listenForThemeChanges() {
        ApplicationManager.getApplication().messageBus
            .connect(this)
            .subscribe(TypstThemeService.TOPIC, TypstThemeListener { _ ->
                ApplicationManager.getApplication().invokeLater {
                    val cef = browser?.cefBrowser ?: return@invokeLater
                    // Theme changes only tweak CSS in-page — no reload, so scroll is preserved.
                    val colorScheme = if (isDark) "dark" else "light"
                    cef.executeJavaScript(
                        "document.documentElement.style.colorScheme='$colorScheme';",
                        cef.url, 0
                    )
                }
            })
    }

    // ---- FileEditor interface ----

    override fun getComponent(): JComponent = browser?.component ?: fallbackLabel
    override fun getPreferredFocusedComponent(): JComponent? = browser?.component
    override fun getName(): String = "Typst Preview"
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
        reloadJob?.cancel(false)
        reloadExecutor.shutdownNow()
        stopWatching()
        // Clean up temp PDF
        if (outputPdf.exists()) {
            outputPdf.delete()
        }
    }

    // ---- typst watch process ----

    private fun startWatching() {
        if (project.isDisposed || !file.isValid) {
            return
        }

        val typstBinary = TinymistManager.getInstance().resolveTypstPath()
        if (typstBinary == null) {
            browser?.loadHTML(waitingHtml(TypstBundle.message("previewer.waiting.downloading")))
            TypstDownloadService.getInstance().downloadInBackground(project) { success ->
                if (project.isDisposed || !file.isValid) return@downloadInBackground

                if (success) {
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed && file.isValid) {
                            startWatching()
                        }
                    }
                } else {
                    browser?.loadHTML(errorHtml(TypstBundle.message("previewer.error.typstMissing")))
                    viewerLoaded = false
                }
            }
            return
        }

        log.info("Starting typst watch for ${file.path} -> ${outputPdf.absolutePath}")
        log.info("Project base path for ${file.path} -> ${project.basePath}")
        val commandLine = GeneralCommandLine(
            buildList {
                add(typstBinary)
                add("watch")
                project.basePath?.let { add("--root"); add(it) }
                add(file.path)
                add(outputPdf.absolutePath)
            }
        ).apply {
            withCharset(Charsets.UTF_8)
            project.basePath?.let { withWorkingDirectory(Path.of(it)) }
        }

        try {
            val handler = object : OSProcessHandler(commandLine) {
                override fun readerOptions(): BaseOutputReader.Options = BaseOutputReader.Options.BLOCKING
            }

            handler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    val text = event.text.trim()
                    if (text.isEmpty()) return

                    log.debug("typst watch ${if (outputType == ProcessOutputTypes.STDERR) "stderr" else "stdout"}: $text")

                    // Route all output to the Typst Output tool window
                    val contentType = when (outputType) {
                        ProcessOutputTypes.STDERR -> ConsoleViewContentType.ERROR_OUTPUT
                        ProcessOutputTypes.SYSTEM -> ConsoleViewContentType.SYSTEM_OUTPUT
                        else -> ConsoleViewContentType.NORMAL_OUTPUT
                    }
                    getConsoleView()?.print(event.text, contentType)

                    // Show errors in the browser panel and open the tool window
                    if (outputType == ProcessOutputTypes.STDERR && text.contains("error", ignoreCase = true)) {
                        ApplicationManager.getApplication().invokeLater {
                            if (!project.isDisposed) {
                                ToolWindowManager.getInstance(project).getToolWindow(TYPST_OUTPUT_TOOL_WINDOW_ID)?.show()
                                browser?.loadHTML(errorHtml(TypstBundle.message("previewer.error.compileFailed")))
                                viewerLoaded = false
                            }
                        }
                    }

                    // When typst finishes a compilation, reload the PDF
                    if (text.contains("writing to", ignoreCase = true) ||
                        text.contains("compiled", ignoreCase = true)) {
                        scheduleReloadPdf()
                    }
                }

                override fun processTerminated(event: ProcessEvent) {
                    getConsoleView()?.print(
                        TypstBundle.message("console.preview.terminated", event.exitCode),
                        ConsoleViewContentType.SYSTEM_OUTPUT
                    )
                }
            })

            handler.startNotify()
            processHandler = handler
            log.info("Started typst watch for ${file.path} -> ${outputPdf.absolutePath}")

            // If a PDF already exists from a previous session, show it immediately
            if (outputPdf.exists() && outputPdf.length() > 0) {
                scheduleReloadPdf()
            } else {
                browser?.loadHTML(waitingHtml())
            }
        } catch (t: Throwable) {
            log.warn("Failed to start typst watch for ${file.path}", t)
            // Bundle messages are author-controlled and may contain HTML (e.g. <br>);
            // exception details are uncontrolled, so escape them BEFORE substitution
            // into the bundle template so the resulting HTML is safe and the bundle's
            // intentional tags survive intact.
            val safeDetails = StringUtil.escapeXmlEntities(t.message ?: t::class.java.name)
            browser?.loadHTML(
                errorHtml(
                    TypstBundle.message("previewer.error.startFailed", safeDetails)
                )
            )
            viewerLoaded = false
        }
    }

    private fun getConsoleView(): ConsoleView? {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TYPST_OUTPUT_TOOL_WINDOW_ID) ?: return null
        val content = toolWindow.contentManager.getContent(0) ?: return null
        return content.component as? ConsoleView
    }

    private fun stopWatching() {
        processHandler?.let {
            if (!it.isProcessTerminated) {
                it.destroyProcess()
            }
        }
        processHandler = null
    }

    // ---- PDF reload via VFS listener (catches disk changes) ----

    private fun listenForPdfChanges() {
        val connection = project.messageBus.connect(this)
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                for (event in events) {
                    if (event.path == outputPdf.absolutePath) {
                        scheduleReloadPdf()
                        break
                    }
                }
            }
        })
    }

    // ---- Reload the PDF in the JCEF browser ----

    /** Debounce: coalesces rapid reload requests (e.g. "writing to" + "compiled" arriving together). */
    private fun scheduleReloadPdf() {
        reloadJob?.cancel(false)
        reloadJob = reloadExecutor.schedule(::reloadPdf, 300, TimeUnit.MILLISECONDS)
    }

    private fun reloadPdf() {
        if (!jcefSupported || browser == null) return
        if (!outputPdf.exists() || outputPdf.length() == 0L) return

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            // Cache-bust via query param so the browser re-fetches the PDF on each compile.
            val pdfUrl = PdfjsEndpoints.pdfUrl(previewerId, System.currentTimeMillis())

            if (viewerLoaded) {
                // Hot-swap: ask PDF.js to load the new PDF in-place. The bridge snapshots
                // the current viewport as "pendingRestore" before swapping, so scroll is preserved.
                val cef = browser.cefBrowser
                cef.executeJavaScript("window.__typstOpenPdf && window.__typstOpenPdf('$pdfUrl');", cef.url, 0)
                log.debug("[viewport] hot-swap PDF via bridge — url=$pdfUrl")
            } else {
                // First load: navigate to the PDF.js viewer with the compiled PDF as its ?file= arg.
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
        val (bg, fg, fgSub) = if (isDark) Triple("#2b2b2b", "#aaaaaa", "#777777")
        else Triple("#f5f5f5", "#555555", "#888888")
        return """
            <html>
            <body style="display:flex;align-items:center;justify-content:center;height:100vh;margin:0;
                         font-family:-apple-system,BlinkMacSystemFont,sans-serif;color:$fg;background:$bg;">
                <div style="text-align:center;">
                    <p style="font-size:16px;">$message</p>
                    <p style="font-size:13px;color:$fgSub;">$detail</p>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    /**
     * Renders an error page in the preview pane. The [message] is treated as
     * already-safe HTML — bundle messages can include intentional tags like
     * `<br>` for line breaks. Callers passing user-supplied content (e.g. an
     * exception's `message`) are responsible for escaping it via
     * [StringUtil.escapeXmlEntities] before substituting it into the bundle
     * template, so the bundle's tags survive while uncontrolled input is safe.
     */
    private fun errorHtml(message: String): String {
        val (bg, fgSub) = if (isDark) Pair("#2b2b2b", "#aaaaaa")
        else Pair("#f5f5f5", "#666666")
        return """
            <html>
            <body style="display:flex;align-items:center;justify-content:center;height:100vh;margin:0;
                         font-family:-apple-system,BlinkMacSystemFont,sans-serif;color:#cc4444;background:$bg;">
                <div style="text-align:center;max-width:500px;padding:20px;">
                    <p style="font-size:16px;font-weight:bold;">${TypstBundle.message("previewer.error.title")}</p>
                    <p style="font-size:13px;color:$fgSub;">$message</p>
                </div>
            </body>
            </html>
        """.trimIndent()
    }
}

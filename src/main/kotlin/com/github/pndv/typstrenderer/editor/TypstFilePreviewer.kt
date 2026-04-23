package com.github.pndv.typstrenderer.editor

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
import org.cef.handler.CefLoadHandlerAdapter
import java.beans.PropertyChangeListener
import java.io.File
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

    private var currentPdfUrl: String? = null
    private val jcefSupported = JBCefApp.isSupported()

    //    private val browser: JCEFHtmlPanel? = if (jcefSupported) JCEFHtmlPanel(currentPdfUrl) else null
    private val browser: JBCefBrowser? = if (jcefSupported) JBCefBrowser() else null
    private val fallbackLabel = JBLabel("JCEF is not supported — PDF preview is unavailable.", SwingConstants.CENTER)

    private var processHandler: OSProcessHandler? = null
    private val outputPdf: File
    private val reloadExecutor = AppExecutorUtil.createBoundedScheduledExecutorService("TypstPdfReload", 1)
    private var reloadJob: ScheduledFuture<*>? = null

    private val isDark get() = EditorColorsManager.getInstance().isDarkEditor

    /** Last viewport reported by Chromium's PDF viewer via postMessage. Reapplied on every reload. */
    @Volatile
    private var lastViewport: PdfViewportState? = null

    /** JS→Kotlin bridge: JS calls this with `{"page":N,"yOffset":D}` each time the viewport changes. */
    private val viewportQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as com.intellij.ui.jcef.JBCefBrowserBase) }

    init {
        // Output PDF goes next to the source file in a temp dir to avoid polluting the project
        val tempDir = File(System.getProperty("java.io.tmpdir"), "typst-preview")
        tempDir.mkdirs()
        val baseName = file.nameWithoutExtension
        outputPdf = File(tempDir, "${baseName}_${file.path.hashCode()}.pdf")

        if (jcefSupported) {
            browser?.let { Disposer.register(this, it) }
            viewportQuery?.let { q ->
                Disposer.register(this, q)
                q.addHandler { json ->
                    log.info("[viewport] payload from JS: $json")
                    val parsed = PdfViewportState.fromJson(json)
                    if (parsed != null) {
                        lastViewport = parsed
                        log.info("[viewport] lastViewport updated -> page=${parsed.page}, yOffset=${parsed.yOffset}")
                    } else {
                        log.info("[viewport] payload did NOT parse as viewport state — treating as diagnostic only")
                    }
                    null
                }
            }
            installLoadEndHandlers()
            listenForThemeChanges()
            startWatching()
            listenForPdfChanges()
        }
    }

    /**
     * Installs the `onLoadEnd` handler that runs every time a page finishes loading in the browser.
     * Two jobs:
     *  1. Inject `color-scheme: dark` so Chromium's built-in PDF viewer picks up dark mode.
     *  2. Install a `message` listener that captures viewport updates from the PDF viewer
     *     (page + vertical scroll) and reports them to Kotlin via [viewportQuery].
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
                if (isDark) {
                    b.executeJavaScript(
                        "document.documentElement.style.colorScheme='dark';",
                        b.url, 0
                    )
                }
                viewportQuery?.let { q ->
                    b.executeJavaScript(buildViewportCaptureScript(q), b.url, 0)
                }
            }
        }, cefBrowser)
    }

    /**
     * Builds the JS that listens for Chromium PDF viewer viewport messages and forwards them
     * to Kotlin. Throttled to ~150ms so rapid scrolls don't flood the bridge.
     *
     * Chromium's built-in PDF viewer posts messages of the form
     * `{type: 'viewport', pageNumber: N, ...}` to its host window on scroll/page changes.
     * Event shape may vary by Chromium version; the narrow `type === 'viewport'` guard
     * keeps this safe — worst case we miss updates and the fragment is omitted.
     */
    private fun buildViewportCaptureScript(q: JBCefJSQuery): String {
        // `q.inject('payload')` produces the JS snippet that invokes the Kotlin handler.
        val reportCall = q.inject("payload")
        val jScript = """
            (function() {
              if (window.__typstViewportHooked) return;
              window.__typstViewportHooked = true;
              var lastSent = 0;
              var pending = null;
              function send(obj) {
                var payload = JSON.stringify(obj);
                $reportCall
              }
              function flushViewport() {
                if (!pending) return;
                var obj = pending;
                pending = null;
                lastSent = Date.now();
                send(obj);
              }
              // === DIAGNOSTIC: log every postMessage event so we can see the real schema ===
              window.addEventListener('message', function(e) {
                try {
                  var d = e && e.data;
                  var diag = {
                    __diag: true,
                    dataType: typeof d,
                    type: (d && d.type) || null,
                    keys: (d && typeof d === 'object') ? Object.keys(d).slice(0, 20) : [],
                    preview: (typeof d === 'string') ? d.slice(0, 200) : null
                  };
                  send(diag);
                } catch (err) { /* ignore */ }

                // === Real capture: only act on viewport-typed messages ===
                var d2 = e && e.data;
                if (!d2 || d2.type !== 'viewport') return;
                var page = d2.pageNumber;
                if (typeof page !== 'number') return;
                var y = (d2.viewportHeight && d2.pageY) ? d2.pageY
                      : (typeof d2.verticalScroll === 'number') ? d2.verticalScroll
                      : 0;
                pending = { page: page, yOffset: y };
                var now = Date.now();
                if (now - lastSent >= 150) flushViewport();
                else setTimeout(flushViewport, 150 - (now - lastSent));
              });
              // === DIAGNOSTIC: also scan DOM for a PDF <embed> every 2s and log anything we find ===
              setInterval(function() {
                try {
                  var e = document.querySelector('embed, iframe');
                  if (!e) return;
                  send({
                    __diag: true,
                    source: 'dom-scan',
                    tag: e.tagName,
                    type: e.getAttribute && e.getAttribute('type'),
                    src: (e.src || '').slice(0, 120),
                    currentPage: e.currentPage,
                    pageCount: e.pageCount,
                    hash: window.location && window.location.hash
                  });
                } catch (err) { /* ignore */ }
              }, 2000);
            })();
        """.trimIndent()
        log.debug("Generated viewport capture script: \n{}", jScript)
        return jScript
    }

    /** Reloads the browser content with updated theme colours whenever the IDE theme changes. */
    private fun listenForThemeChanges() {
        ApplicationManager.getApplication().messageBus
            .connect(this)
            .subscribe(TypstThemeService.TOPIC, TypstThemeListener { _ ->
                ApplicationManager.getApplication().invokeLater {
                    val url = currentPdfUrl
                    if (url != null) {
                        browser?.loadURL(url + viewportFragment())
                    }
                }
            })
    }

    /** Returns the URL hash fragment to restore the last-known viewport, or empty if none. */
    private fun viewportFragment(): String = lastViewport?.toUrlFragment() ?: ""

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
            browser?.loadHTML(waitingHtml("Downloading Typst CLI..."))
            TypstDownloadService.getInstance().downloadInBackground(project) { success ->
                if (project.isDisposed || !file.isValid) return@downloadInBackground

                if (success) {
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed && file.isValid) {
                            startWatching()
                        }
                    }
                } else {
                    browser?.loadHTML(
                        errorHtml(
                            "Typst CLI not found and auto-download failed. " +
                                    "Install it or configure the path in Settings &gt; Tools &gt; Typst."
                        )
                    )
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
            project.basePath?.let { withWorkDirectory(it) }
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
                                ToolWindowManager.getInstance(project).getToolWindow("Typst Output")?.show()
                                browser?.loadHTML(errorHtml("Compilation error — see the Typst Output panel for details."))
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
                        "\nPreview process terminated with exit code ${event.exitCode}\n",
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
            browser?.loadHTML(
                errorHtml(
                    "Failed to start Typst preview.<br>" +
                            "Check that the Typst executable is valid and runnable.<br>" +
                            "Details: ${t.message ?: t::class.java.name}"
                )
            )
        }
    }

    private fun getConsoleView(): ConsoleView? {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Typst Output") ?: return null
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

        val fileUrl = outputPdf.toURI().toString()
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            // Cache-bust via query param so Chromium re-fetches the PDF from disk.
            // Fragment (#page=…) is preserved by the viewer separately.
            val cacheBust = "?v=${System.currentTimeMillis()}"
            val fragment = viewportFragment()
            val urlToLoad = fileUrl + cacheBust + fragment

            currentPdfUrl = fileUrl
            log.info("[viewport] reloading PDF — lastViewport=$lastViewport — url=$urlToLoad")
            browser.loadURL(urlToLoad)
        }
    }

    // ---- Utility HTML pages ----

    private fun waitingHtml(
        message: String = "Compiling...",
        detail: String = "Waiting for typst to generate the PDF."
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

    private fun errorHtml(message: String): String {
        val (bg, fgSub) = if (isDark) Pair("#2b2b2b", "#aaaaaa")
        else Pair("#f5f5f5", "#666666")
        return """
            <html>
            <body style="display:flex;align-items:center;justify-content:center;height:100vh;margin:0;
                         font-family:-apple-system,BlinkMacSystemFont,sans-serif;color:#cc4444;background:$bg;">
                <div style="text-align:center;max-width:500px;padding:20px;">
                    <p style="font-size:16px;font-weight:bold;">Preview Error</p>
                    <p style="font-size:13px;color:$fgSub;">${message.replace("<", "&lt;")}</p>
                </div>
            </body>
            </html>
        """.trimIndent()
    }
}

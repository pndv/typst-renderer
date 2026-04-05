package com.github.pndv.typstrenderer.editor

import com.github.pndv.typstrenderer.lsp.TinymistManager
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefApp
import java.beans.PropertyChangeListener
import java.io.File
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingConstants

private val LOG = logger<TypstPreviewFileEditor>()

/**
 * A file editor that shows a live PDF preview of a Typst file.
 *
 * Runs `typst watch <input> <output.pdf>` in the background and renders
 * the output PDF in a JCEF (embedded Chromium) browser panel.
 * The preview auto-refreshes whenever typst recompiles the PDF.
 */
class TypstPreviewFileEditor(
    private val project: Project,
    private val file: VirtualFile
) : UserDataHolderBase(), FileEditor {

    private val jcefSupported = JBCefApp.isSupported()
    private val browser: JBCefBrowser? = if (jcefSupported) JBCefBrowser() else null
    private val fallbackLabel = JLabel("JCEF is not supported — PDF preview is unavailable.", SwingConstants.CENTER)

    private var processHandler: OSProcessHandler? = null
    private val outputPdf: File

    init {
        // Output PDF goes next to the source file in a temp dir to avoid polluting the project
        val tempDir = File(System.getProperty("java.io.tmpdir"), "typst-preview")
        tempDir.mkdirs()
        val baseName = file.nameWithoutExtension
        outputPdf = File(tempDir, "${baseName}_${file.path.hashCode()}.pdf")

        if (jcefSupported) {
            browser?.let { Disposer.register(this, it) }
            startWatching()
            listenForPdfChanges()
        }
    }

    // ---- FileEditor interface ----

    override fun getComponent(): JComponent = browser?.component ?: fallbackLabel
    override fun getPreferredFocusedComponent(): JComponent? = browser?.component
    override fun getName(): String = "Typst Preview"
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = file.isValid

    override fun setState(state: FileEditorState) {}
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun getFile(): VirtualFile = file

    override fun dispose() {
        stopWatching()
        // Clean up temp PDF
        if (outputPdf.exists()) {
            outputPdf.delete()
        }
    }

    // ---- typst watch process ----

    private fun startWatching() {
        val typstBinary = TinymistManager.getInstance().resolveTypstPath()
        if (typstBinary == null) {
            browser?.loadHTML(errorHtml("Typst CLI not found. Install it or configure the path in Settings > Tools > Typst."))
            return
        }

        val commandLine = GeneralCommandLine(
            typstBinary, "watch", file.path, outputPdf.absolutePath
        ).apply {
            withCharset(Charsets.UTF_8)
            project.basePath?.let { withWorkDirectory(it) }
        }

        try {
            val handler = OSProcessHandler(commandLine)

            handler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    val text = event.text.trim()
                    if (text.isEmpty()) return

                    if (outputType == ProcessOutputTypes.STDERR && text.contains("error", ignoreCase = true)) {
                        LOG.info("typst watch stderr: $text")
                    }

                    // When typst finishes a compilation, reload the PDF
                    if (text.contains("writing to", ignoreCase = true) || text.contains("compiled", ignoreCase = true)) {
                        reloadPdf()
                    }
                }

                override fun processTerminated(event: ProcessEvent) {
                    LOG.info("typst watch process terminated with exit code ${event.exitCode}")
                }
            })

            handler.startNotify()
            processHandler = handler
            LOG.info("Started typst watch for ${file.path} -> ${outputPdf.absolutePath}")

            // If a PDF already exists from a previous session, show it immediately
            if (outputPdf.exists() && outputPdf.length() > 0) {
                reloadPdf()
            } else {
                browser?.loadHTML(waitingHtml())
            }
        } catch (e: Exception) {
            LOG.warn("Failed to start typst watch", e)
            browser?.loadHTML(errorHtml("Failed to start typst watch: ${e.message}"))
        }
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
                        reloadPdf()
                        break
                    }
                }
            }
        })
    }

    // ---- Reload the PDF in the JCEF browser ----

    private fun reloadPdf() {
        if (!jcefSupported || browser == null) return
        if (!outputPdf.exists() || outputPdf.length() == 0L) return

        // JCEF/Chromium has a built-in PDF viewer; just load the file URL
        val fileUrl = outputPdf.toURI().toString()
        browser.loadURL(fileUrl)
    }

    // ---- Utility HTML pages ----

    private fun waitingHtml(): String = """
        <html>
        <body style="display:flex;align-items:center;justify-content:center;height:100vh;margin:0;
                     font-family:-apple-system,BlinkMacSystemFont,sans-serif;color:#888;background:#2b2b2b;">
            <div style="text-align:center;">
                <p style="font-size:16px;">Compiling...</p>
                <p style="font-size:13px;">Waiting for typst to generate the PDF.</p>
            </div>
        </body>
        </html>
    """.trimIndent()

    private fun errorHtml(message: String): String = """
        <html>
        <body style="display:flex;align-items:center;justify-content:center;height:100vh;margin:0;
                     font-family:-apple-system,BlinkMacSystemFont,sans-serif;color:#cc4444;background:#2b2b2b;">
            <div style="text-align:center;max-width:500px;padding:20px;">
                <p style="font-size:16px;font-weight:bold;">Preview Error</p>
                <p style="font-size:13px;color:#aaa;">${message.replace("<", "&lt;")}</p>
            </div>
        </body>
        </html>
    """.trimIndent()
}

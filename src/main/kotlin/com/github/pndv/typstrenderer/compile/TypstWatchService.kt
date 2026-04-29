package com.github.pndv.typstrenderer.compile

import com.github.pndv.typstrenderer.lsp.TinymistManager
import com.github.pndv.typstrenderer.lsp.TypstDownloadService
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindowManager

@Service(Service.Level.PROJECT)
class TypstWatchService(private val project: Project) : Disposable {

    private var processHandler: OSProcessHandler? = null
    private var watchedFile: String? = null

    val isWatching: Boolean
        get() = processHandler?.isProcessTerminated == false && processHandler?.isProcessTerminating == false

    fun startWatch(inputPath: String) {
        stopWatch()

        val typstBinary = TinymistManager.getInstance().resolveTypstPath()
        if (typstBinary == null) {
            TypstDownloadService.getInstance().downloadInBackground(project) { success ->
                if (success) {
                    startWatch(inputPath)
                }
            }
            return
        }

        val commandLine = GeneralCommandLine(typstBinary, "watch", inputPath).apply {
            withCharset(Charsets.UTF_8)
            project.basePath?.let { withWorkDirectory(it) }
        }

        val handler = OSProcessHandler(commandLine)
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                val contentType = when (outputType) {
                    ProcessOutputTypes.STDERR -> ConsoleViewContentType.ERROR_OUTPUT
                    ProcessOutputTypes.SYSTEM -> ConsoleViewContentType.SYSTEM_OUTPUT
                    else -> ConsoleViewContentType.NORMAL_OUTPUT
                }
                getConsoleView()?.print(event.text, contentType)
            }

            override fun processTerminated(event: ProcessEvent) {
                getConsoleView()?.print(
                    "\nWatch process terminated with exit code ${event.exitCode}\n",
                    ConsoleViewContentType.SYSTEM_OUTPUT
                )
            }
        })

        getConsoleView()?.clear()
        getConsoleView()?.print("Watching $inputPath for changes...\n", ConsoleViewContentType.SYSTEM_OUTPUT)

        handler.startNotify()
        processHandler = handler
        watchedFile = inputPath

        ToolWindowManager.getInstance(project).getToolWindow("Typst Output")?.show()
    }

    fun stopWatch() {
        processHandler?.let {
            if (!it.isProcessTerminated) {
                it.destroyProcess()
            }
        }
        processHandler = null
        watchedFile = null
    }

    private fun getConsoleView(): ConsoleView? {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Typst Output") ?: return null
        val content = toolWindow.contentManager.getContent(0) ?: return null
        return content.component as? ConsoleView
    }

    override fun dispose() {
        stopWatch()
    }
}

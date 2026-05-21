package com.github.pndv.typstrenderer.compile

import com.github.pndv.typstrenderer.Common.clearConsoleView
import com.github.pndv.typstrenderer.Common.printToConsole
import com.github.pndv.typstrenderer.TYPST_OUTPUT_TOOL_WINDOW_ID
import com.github.pndv.typstrenderer.TypstBundle
import com.github.pndv.typstrenderer.lsp.TinymistManager
import com.github.pndv.typstrenderer.lsp.TypstDownloadService
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindowManager
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class TypstWatchService(private val project: Project) : Disposable {

    private var processHandler: OSProcessHandler? = null
    private var watchedFile: String? = null
    private val log = logger<TypstWatchService>()

    val isWatching: Boolean
        get() = processHandler?.isProcessTerminated == false && processHandler?.isProcessTerminating == false

    fun startWatch(inputPath: String) {
        // Record the target before the binary check (same reasoning as
        // TypstCompileService.compile) so the Toggle Watch toolbar action
        // and the Recompile action can both target this file even if the
        // first start triggered a typst download.
        log.debug { "TypstWatchService will track $inputPath" }
        project.service<TypstLastCompiledTracker>().record(inputPath)

        log.debug {"Starting watch for $inputPath. First, stop watching ..."}

        stopWatch()

        log.debug {"Watch stopped for $inputPath. Now resuming..."}

        val typstBinary = TinymistManager.getInstance().resolveTypstPath()
        log.debug {"Resolved typst binary: $typstBinary"}

        if (typstBinary == null) {
            TypstDownloadService.getInstance().downloadInBackground(project) { success ->
                if (success) {
                    startWatch(inputPath)
                }
            }
            return
        }

        val commandLine = GeneralCommandLine(
            buildList {
                add(typstBinary)
                add("watch")
                project.basePath?.let { add("--root"); add(it) }
                add(inputPath)
            }
        ).apply {
            withCharset(Charsets.UTF_8)
            project.basePath?.let { withWorkingDirectory(Path.of(it)) }
        }

        log.debug {"Typst Watch Service: CommandLine is: $commandLine"}

        val handler = OSProcessHandler(commandLine)
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                val contentType = when (outputType) {
                    ProcessOutputTypes.STDERR -> ConsoleViewContentType.ERROR_OUTPUT
                    ProcessOutputTypes.SYSTEM -> ConsoleViewContentType.SYSTEM_OUTPUT
                    else -> ConsoleViewContentType.NORMAL_OUTPUT
                }
                printToConsole(project, log, event.text, contentType)
            }

            override fun processTerminated(event: ProcessEvent) {
                printToConsole(project, log,
                    TypstBundle.message("console.watch.terminated", event.exitCode),
                    ConsoleViewContentType.SYSTEM_OUTPUT
                )
            }
        })

        clearConsoleView(project, log)
        printToConsole(project, log,
            TypstBundle.message("console.watch.starting", inputPath),
            ConsoleViewContentType.SYSTEM_OUTPUT
        )

        handler.startNotify()
        processHandler = handler
        watchedFile = inputPath

        ToolWindowManager.getInstance(project).getToolWindow(TYPST_OUTPUT_TOOL_WINDOW_ID)?.show()
    }

    fun stopWatch() {
        log.debug {"Stopping Typst Watch Service"}
        processHandler?.let {
            if (!it.isProcessTerminated) {
                it.destroyProcess()
            }
        }
        processHandler = null
        watchedFile = null
        log.debug("Stopped Typst Watch Service")
    }

    override fun dispose() {
        stopWatch()
    }
}

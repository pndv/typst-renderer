package com.github.pndv.typstrenderer.compile

import com.github.pndv.typstrenderer.Common.printToConsole
import com.github.pndv.typstrenderer.TYPST_NOTIFICATION_GROUP_ID
import com.github.pndv.typstrenderer.TypstBundle
import com.github.pndv.typstrenderer.lsp.TinymistManager
import com.github.pndv.typstrenderer.lsp.TypstDownloadService
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class TypstCompileService(private val project: Project) {
    private val log = logger<TypstCompileService>()

    fun compile(inputPath: String, outputPath: String? = null) {
        // Record the target before the binary check so a download-and-retry
        // also tracks the user's intent — the Recompile toolbar action then
        // works even if the very first compile attempt triggered the typst
        // download flow rather than reaching the actual compile.
        log.debug { "TypstCompileService will track $inputPath for compilation" }
        project.service<TypstLastCompiledTracker>().record(inputPath)

        val typstBinary = TinymistManager.getInstance().resolveTypstPath()
        if (typstBinary == null) {
            TypstDownloadService.getInstance().downloadInBackground(project) { success ->
                if (success) {
                    compile(inputPath, outputPath)
                } else {
                    // Toolchain-setup failure: the user has never compiled, so the
                    // Typst Output tool window may not be visible (or even initialised
                    // yet). A balloon is the only place they're guaranteed to see this.
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup(TYPST_NOTIFICATION_GROUP_ID)
                        .createNotification(
                            TypstBundle.message("notification.typst.notFound.title"),
                            TypstBundle.message("notification.typst.notFound.body"),
                            NotificationType.ERROR
                        )
                        .notify(project)
                }
            }
            return
        }

        val commandLine = TypstCommandBuilder.buildCompileCommand(
            binary = typstBinary,
            inputPath = inputPath,
            project = project,
            outputPath = outputPath,
        )

        // Immediate feedback in the tool window before the pooled-thread work starts;
        // also pops the window open so the user sees subsequent outcome messages.
        printToConsole(project, log,
                       TypstBundle.message("console.compile.starting", inputPath),
            ConsoleViewContentType.SYSTEM_OUTPUT
        )

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val handler = CapturingProcessHandler(commandLine)
                val result = handler.runProcess(30_000)

                if (result.exitCode == 0) {
                    val pdfPath = outputPath ?: (inputPath.removeSuffix(".typ") + ".pdf")
                    printToConsole(project, log,
                                   TypstBundle.message("console.compile.success", pdfPath),
                        ConsoleViewContentType.SYSTEM_OUTPUT
                    )
                } else {
                    val stderr = result.stderr.ifBlank { result.stdout }
                    printToConsole(project, log,
                                   TypstBundle.message("console.compile.failed", stderr),
                        ConsoleViewContentType.ERROR_OUTPUT
                    )
                }
            } catch (e: Exception) {
                printToConsole(project, log,
                    TypstBundle.message("console.compile.error", e.message ?: ""),
                    ConsoleViewContentType.ERROR_OUTPUT
                )
            }
        }
    }
}

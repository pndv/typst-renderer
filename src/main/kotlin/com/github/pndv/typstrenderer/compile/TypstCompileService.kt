package com.github.pndv.typstrenderer.compile

import com.github.pndv.typstrenderer.lsp.TinymistManager
import com.github.pndv.typstrenderer.lsp.TypstDownloadService
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

@Service(Service.Level.PROJECT)
class TypstCompileService(private val project: Project) {

    fun compile(inputPath: String, outputPath: String? = null) {
        val typstBinary = TinymistManager.getInstance().resolveTypstPath()
        if (typstBinary == null) {
            TypstDownloadService.getInstance().downloadInBackground(project) { success ->
                if (success) {
                    compile(inputPath, outputPath)
                } else {
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("Typst")
                        .createNotification(
                            "Typst not found",
                            "Typst CLI not found and auto-download failed. Configure the path in Settings > Tools > Typst.",
                            NotificationType.ERROR
                        ).notify(project)
                }
            }
            return
        }

        val command = mutableListOf(typstBinary, "compile", inputPath)
        if (outputPath != null) {
            command.add(outputPath)
        }

        val commandLine = GeneralCommandLine(command).apply {
            withCharset(Charsets.UTF_8)
            project.basePath?.let { withWorkDirectory(it) }
        }

        try {
            val handler = CapturingProcessHandler(commandLine)
            val result = handler.runProcess(30_000)

            val notificationGroup = NotificationGroupManager.getInstance()
                .getNotificationGroup("Typst")

            if (result.exitCode == 0) {
                val pdfPath = outputPath ?: (inputPath.removeSuffix(".typ") + ".pdf")
                notificationGroup.createNotification(
                    "Typst",
                    "Compiled successfully: $pdfPath",
                    NotificationType.INFORMATION
                ).notify(project)
            } else {
                val stderr = result.stderr.ifBlank { result.stdout }
                notificationGroup.createNotification(
                    "Typst compilation failed",
                    stderr,
                    NotificationType.ERROR
                ).notify(project)
                printErrorToConsole("Compilation failed:\n$stderr\n")
            }
        } catch (e: Exception) {
            val message = "Failed to run typst: ${e.message}"
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Typst")
                .createNotification(
                    "Typst error",
                    message,
                    NotificationType.ERROR
                ).notify(project)
            printErrorToConsole("$message\n")
        }
    }

    private fun printErrorToConsole(text: String) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow("Typst Output") ?: return@invokeLater
            toolWindow.show()
            val content = toolWindow.contentManager.getContent(0) ?: return@invokeLater
            (content.component as? ConsoleView)?.print(text, ConsoleViewContentType.ERROR_OUTPUT)
        }
    }
}

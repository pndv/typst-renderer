package com.github.pndv.typstrenderer.compile

import com.github.pndv.typstrenderer.lsp.TinymistManager
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class TypstCompileService(private val project: Project) {

    fun compile(inputPath: String, outputPath: String? = null) {
        val typstBinary = TinymistManager.getInstance().resolveTypstPath()
        if (typstBinary == null) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Typst")
                .createNotification(
                    "Typst not found",
                    "Typst CLI not found. Install it via: cargo install typst-cli, or configure the path in Settings > Tools > Typst.",
                    NotificationType.ERROR
                ).notify(project)
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
            }
        } catch (e: Exception) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Typst")
                .createNotification(
                    "Typst error",
                    "Failed to run typst: ${e.message}",
                    NotificationType.ERROR
                ).notify(project)
        }
    }
}

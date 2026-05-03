package com.github.pndv.typstrenderer.compile

import com.github.pndv.typstrenderer.TYPST_NOTIFICATION_GROUP_ID
import com.github.pndv.typstrenderer.TYPST_OUTPUT_TOOL_WINDOW_ID
import com.github.pndv.typstrenderer.TypstBundle
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
import java.nio.file.Path

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

        val commandLine = GeneralCommandLine(buildList {
            add(typstBinary)
            add("compile")
            project.basePath?.let { add("--root"); add(it) }
            add(inputPath)
            outputPath?.let { add(it) }
        }).apply {
            withCharset(Charsets.UTF_8)
            project.basePath?.let { withWorkingDirectory(Path.of(it)) }
        }

        try {
            val handler = CapturingProcessHandler(commandLine)
            val result = handler.runProcess(30_000)

            val notificationGroup =
                NotificationGroupManager.getInstance().getNotificationGroup(TYPST_NOTIFICATION_GROUP_ID)

            if (result.exitCode == 0) {
                val pdfPath = outputPath ?: (inputPath.removeSuffix(".typ") + ".pdf")
                notificationGroup.createNotification(
                    TypstBundle.message("notification.compile.success.title"),
                    TypstBundle.message("notification.compile.success.body", pdfPath),
                    NotificationType.INFORMATION
                ).notify(project)
            } else {
                val stderr = result.stderr.ifBlank { result.stdout }
                notificationGroup.createNotification(
                    TypstBundle.message("notification.compile.failed.title"), stderr, NotificationType.ERROR
                ).notify(project)
                printErrorToConsole(TypstBundle.message("console.compile.failed", stderr))
            }
        } catch (e: Exception) {
            val message = TypstBundle.message("notification.compile.error.body", e.message ?: "")
            NotificationGroupManager.getInstance().getNotificationGroup(TYPST_NOTIFICATION_GROUP_ID).createNotification(
                    TypstBundle.message("notification.compile.error.title"), message, NotificationType.ERROR
                ).notify(project)
            printErrorToConsole("$message\n")
        }
    }

    private fun printErrorToConsole(text: String) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val toolWindow =
                ToolWindowManager.getInstance(project).getToolWindow(TYPST_OUTPUT_TOOL_WINDOW_ID) ?: return@invokeLater
            toolWindow.show()
            val content = toolWindow.contentManager.getContent(0) ?: return@invokeLater
            (content.component as? ConsoleView)?.print(text, ConsoleViewContentType.ERROR_OUTPUT)
        }
    }
}

package com.github.pndv.typstrenderer

import com.github.pndv.typstrenderer.toolWindow.TypstConsoleHolder
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.project.Project

object Common {
    internal fun getConsoleView(project: Project, log: Logger): ConsoleView?  {
        log.debug { "Getting console view for project ${project.name}"}
        val console = project.service<TypstConsoleHolder>().console
        if (console == null) {
            log.warn("Could not get console view for project ${project.name}. The console is null.")
        }
        return console
    }

    internal fun printToConsole(project: Project, log: Logger, message: String, contentType: ConsoleViewContentType) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            val consoleView = getConsoleView(project, log)
            if (consoleView == null) {
                log.debug {"Could not print to console: $message. The console is null."}
                return@invokeLater
            }

            consoleView.print(message, contentType)
        }
    }

    internal fun clearConsoleView(project: Project, log: Logger) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val consoleView = getConsoleView(project, log) ?: return@invokeLater
            consoleView.clear()
        }
    }
}

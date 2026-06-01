package com.github.pndv.typstrenderer.actions

import com.github.pndv.typstrenderer.TypstBundle
import com.github.pndv.typstrenderer.compile.TypstWatchService
import com.github.pndv.typstrenderer.language.TypstFileType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service

class TypstWatchAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val watchService = project.service<TypstWatchService>()

        if (watchService.isWatching) {
            watchService.stopWatch()
        } else {
            val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
            watchService.startWatch(file.path)
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)

        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val watchService = project.service<TypstWatchService>()
        if (watchService.isWatching) {
            e.presentation.isEnabledAndVisible = true
            e.presentation.text = TypstBundle.message("action.Typst.Watch.text.stop")
        } else {
            e.presentation.isEnabledAndVisible = file?.fileType == TypstFileType
            e.presentation.text = TypstBundle.message("action.Typst.Watch.text")
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

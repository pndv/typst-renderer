package com.github.pndv.typstrenderer.actions

import com.github.pndv.typstrenderer.TypstBundle
import com.github.pndv.typstrenderer.compile.TypstWatchService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service

/**
 * Toggle-watch action attached to the Typst Output tool window's title bar
 *
 * Mirrors [TypstWatchAction]'s start/stop semantics but resolves its target
 * via [resolveTypstTargetFromOutputContext] (same as
 * [TypstRecompileFromOutputAction]) rather than the action data context.
 *
 * Action text flips between "Watch" (when stopped) and "Stop watching"
 * (when running), matching the existing editor-popup action's UX. While
 * watching, the action is always enabled (stop is always available);
 * while stopped, it's enabled only if a `.typ` target resolves.
 */
class TypstWatchToggleFromOutputAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val watchService = project.service<TypstWatchService>()

        if (watchService.isWatching) {
            watchService.stopWatch()
        } else {
            val target = resolveTypstTargetFromOutputContext(project) ?: return
            watchService.startWatch(target)
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val watchService = project.service<TypstWatchService>()
        e.presentation.isVisible = true
        if (watchService.isWatching) {
            e.presentation.isEnabled = true
            e.presentation.text = TypstBundle.message("action.Typst.WatchToggleFromOutput.text.stop")
        } else {
            e.presentation.isEnabled = resolveTypstTargetFromOutputContext(project) != null
            e.presentation.text = TypstBundle.message("action.Typst.WatchToggleFromOutput.text")
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

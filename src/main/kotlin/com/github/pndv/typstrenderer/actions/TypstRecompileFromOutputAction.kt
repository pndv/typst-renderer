package com.github.pndv.typstrenderer.actions

import com.github.pndv.typstrenderer.compile.TypstCompileService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service

/**
 * Recompile action attached to the Typst Output tool window's title bar
 *
 * Differs from [TypstCompileAction] in target resolution: this action
 * resolves the file via [resolveTypstTargetFromOutputContext] (most
 * recently compiled / watched file, falling back to the currently
 * active editor) rather than reading `CommonDataKeys.VIRTUAL_FILE`
 * from the action data context — which is unset for the tool-window
 * title-bar context.
 *
 * Visibility is always true (the button stays present on the toolbar);
 * `isEnabled` reflects whether a target resolves. Hiding the button
 * when nothing has been compiled yet would surprise users who expect
 * a stable toolbar layout.
 */
class TypstRecompileFromOutputAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = resolveTypstTargetFromOutputContext(project) ?: return
        project.service<TypstCompileService>().compile(target)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isVisible = true
        e.presentation.isEnabled = project != null && resolveTypstTargetFromOutputContext(project) != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

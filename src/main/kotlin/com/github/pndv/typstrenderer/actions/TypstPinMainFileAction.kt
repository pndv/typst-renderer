package com.github.pndv.typstrenderer.actions

import com.github.pndv.typstrenderer.language.TypstFileType
import com.github.pndv.typstrenderer.lsp.TypstMainFilePinner
import com.github.pndv.typstrenderer.settings.TypstProjectSettingsState
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.logger

/**
 * Pins the selected `.typ` file as the project's Typst compile entry (`main.typ`).
 *
 * Persists the absolute path to [TypstProjectSettingsState.typstMainFile] and re-pins
 * the running tinymist server via `tinymist.pinMain`, so diagnostics for any open
 * chapter file derive from compiling this entry — cross-file references resolve instead
 * of reporting bogus "label undefined" errors.
 */
class TypstPinMainFileAction : AnAction() {

    private val log = logger<TypstPinMainFileAction>()

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        log.info("Pinning Typst main file to ${file.path} for project ${project.name}")
        TypstProjectSettingsState.getInstance(project).typstMainFile = file.path
        TypstMainFilePinner.applyConfiguredPin(project)
    }

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = file?.fileType == TypstFileType
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

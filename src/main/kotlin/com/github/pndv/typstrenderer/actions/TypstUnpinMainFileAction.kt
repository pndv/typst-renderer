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
 * Clears the project's pinned Typst compile entry, reverting tinymist to its default
 * focused-file behaviour.
 *
 * Blanks [TypstProjectSettingsState.typstMainFile] and sends `tinymist.pinMain(null)`.
 * Only shown for `.typ` files while a pin is actually set, so the menu stays quiet when
 * there is nothing to unpin.
 */
class TypstUnpinMainFileAction : AnAction() {

    private val log = logger<TypstUnpinMainFileAction>()

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        log.info("Unpinning Typst main file for project ${project.name}")
        TypstProjectSettingsState.getInstance(project).typstMainFile =
            "" // Blank setting → applyConfiguredPin sends pinMain(null) and broadcasts, so open
        // previews drop back from the full document to their own file.
        TypstMainFilePinner.applyConfiguredPin(project)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val hasPin = project != null && TypstProjectSettingsState.getInstance(project).typstMainFile.isNotBlank()
        e.presentation.isEnabledAndVisible = file?.fileType == TypstFileType && hasPin
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

package com.github.pndv.typstrenderer.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.LangDataKeys

/**
 * Clears the Typst Output tool window console.
 *
 * Lives in plugin.xml (not anonymous inline inside the tool-window factory), so its
 * text / description are pulled from [com.github.pndv.typstrenderer.TypstBundle]
 * via the `action.<id>.text` / `action.<id>.description` keys — i.e. localisable.
 *
 * Resolves the target console via the standard platform data key
 * [LangDataKeys.CONSOLE_VIEW], which `ConsoleViewImpl.uiDataSnapshot` populates
 * for any data context rooted at the console component. The tool-window factory
 * sets `toolbar.targetComponent = console.component`, so this lookup succeeds
 * for free.
 */
class TypstClearOutputConsoleAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        e.getData(LangDataKeys.CONSOLE_VIEW)?.clear()
    }

    override fun update(e: AnActionEvent) {
        val console = e.getData(LangDataKeys.CONSOLE_VIEW)
        e.presentation.isEnabled = console != null && console.contentSize > 0
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

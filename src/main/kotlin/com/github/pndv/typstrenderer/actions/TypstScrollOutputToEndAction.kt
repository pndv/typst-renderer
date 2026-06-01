package com.github.pndv.typstrenderer.actions

import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.LangDataKeys

/**
 * Scrolls the Typst Output console to the latest line.
 *
 * Lives in plugin.xml (not anonymous inline inside the tool-window factory), so its
 * text / description are pulled from [com.github.pndv.typstrenderer.TypstBundle]
 * via the `action.<id>.text` / `action.<id>.description` keys — i.e. localisable.
 *
 * Resolves the target console via the standard platform data key
 * [LangDataKeys.CONSOLE_VIEW]; see [TypstClearOutputConsoleAction] for the
 * routing details. Disabled (instead of throwing) if the console is not the
 * concrete [ConsoleViewImpl] — `requestScrollingToEnd` is an impl detail
 * not on the [com.intellij.execution.ui.ConsoleView] interface, so we fail
 * soft if the platform ever swaps the implementation.
 */
class TypstScrollOutputToEndAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        (e.getData(LangDataKeys.CONSOLE_VIEW) as? ConsoleViewImpl)?.requestScrollingToEnd()
    }

    override fun update(e: AnActionEvent) {
        val console = e.getData(LangDataKeys.CONSOLE_VIEW)
        e.presentation.isEnabled = console is ConsoleViewImpl && console.contentSize > 0
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

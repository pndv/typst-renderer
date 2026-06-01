package com.github.pndv.typstrenderer.toolWindow

import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.components.Service

@Service(Service.Level.PROJECT)
class TypstConsoleHolder {
    /** The [TypstOutputToolWindowFactory] wraps the ConsoleView inside a
     * [com.intellij.openapi.ui.SimpleToolWindowPanel] for the left-edge vertical toolbar.
     * So `content.component` is the panel, not the console.
     * We need a way to get the actual ConsoleView back out of the panel.
     */

    @Volatile var console: ConsoleView? = null
}

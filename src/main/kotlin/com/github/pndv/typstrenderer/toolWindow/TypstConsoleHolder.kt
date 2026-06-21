package com.github.pndv.typstrenderer.toolWindow

import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.components.Service

@Service(Service.Level.PROJECT)
class TypstConsoleHolder {
    /** The [TypstOutputToolWindowFactory] wraps the ConsoleView inside a
     * [com.intellij.openapi.ui.SimpleToolWindowPanel] for the left-edge vertical toolbar.
     * So `content.component` is the panel, not the console.
     * We need a way to get the actual ConsoleView back out of the panel.
     *
     * This holder also owns a small pre-open buffer: output produced before the tool
     * window has been created (so [console] is still null) is parked in [buffer] and
     * flushed via [printBufferToConsole] once the console becomes available, so the
     * first compile's diagnostics aren't lost on a freshly-opened project.
     *
     * Buffer access is confined to the EDT (every caller goes through Common's
     * invokeLater wrappers, and the flush runs from createToolWindowContent), so the
     * plain [ArrayDeque] needs no synchronisation.
     */

    @Volatile var console: ConsoleView? = null

    private val buffer: ArrayDeque<Pair<String, ConsoleViewContentType>> = ArrayDeque()

    fun appendToBuffer(message: String, contentType: ConsoleViewContentType) {
        buffer.add(Pair(message, contentType))
    }

    fun clearBuffer() {
        buffer.clear()
    }

    fun printBufferToConsole() {
        val currentConsole = console ?: return

        while (buffer.isNotEmpty()) {
            val message = buffer.first()
            currentConsole.print(message.first, message.second)
            buffer.removeFirst()
        }
    }
}

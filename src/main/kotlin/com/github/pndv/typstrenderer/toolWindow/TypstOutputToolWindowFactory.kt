package com.github.pndv.typstrenderer.toolWindow

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

class TypstOutputToolWindowFactory : ToolWindowFactory {
    private val log = logger<TypstOutputToolWindowFactory>()

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        log.debug { "Creating TypstOutputToolWindowFactory for project ${project.name}" }
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console

        // Hyperlink typst error / warning locations (path:line:col) into the
        // editor. Both output paths — TypstCompileService and TypstFilePreviewer —
        // write into this shared console, so a single filter registration covers
        // every code path that emits typst diagnostics.
        console.addMessageFilter(TypstConsoleFilter(project))

        val consoleHolder = project.service<TypstConsoleHolder>()
        consoleHolder.console = console

        // Drain any output that was produced before the tool window existed (e.g. a compile
        // that finished while the console was still null). printToConsole buffers those
        // messages in the holder; now that the console is live, flush them in order so the
        // first compile's diagnostics aren't lost on a newly opened project.
        consoleHolder.printBufferToConsole()

        // Disposal wiring, half 1 of 2 — the other half is the
        // Disposer.register(content, console) call after the content is added below.
        // Read together: the content owns the console, and the console owns the holder
        // slot that points back at it.
        //
        // Clearing the slot on disposal means later reads see null instead of a stale
        // pointer into a disposed ConsoleViewImpl. Disposer runs children before the
        // parent's own dispose(), so the slot is cleared before the console starts
        // releasing its editor.
        //
        // The identity check matters only if the console is ever rebuilt without an IDE
        // restart (a "reset console" action, a content swap). If the replacement claims
        // the slot before this disposable runs, an unconditional null would wipe the
        // live console's reference — the tool window would keep rendering but never
        // receive a line, with every message falling through to the pre-open buffer
        // instead. `console` is captured, so each disposable only ever retracts its own
        // registration, never a successor's.
        val disposableConsole = Disposable {
            if (consoleHolder.console === console) {
                consoleHolder.console = null
            }
        }
        Disposer.register(console, disposableConsole)

        // Toolbar Actions Layout: vertical strip down the left edge of the
        // tool window, matching the native convention used by Run / Debug / Build / Terminal.
        //
        // Order: [Recompile] | [Clear] [Scroll-to-end]
        //
        // All three actions are registered in plugin.xml (so their text / description come
        // from TypstBundle and are localisable, and they're discoverable from the action
        // search palette + assignable to keyboard shortcuts) and pulled by ID here.
        //
        // Recompile resolves its target file via TypstLastCompiledTracker so it
        // works even when the user has the tool window focused with no .typ editor active.
        //
        // Clear / Scroll-to-end resolve their target console via LangDataKeys.CONSOLE_VIEW,
        // which ConsoleViewImpl.uiDataSnapshot populates automatically — combined with
        // toolbar.targetComponent = console.component below, the data context just works.
        val actionManager = ActionManager.getInstance()
        val actionGroup = DefaultActionGroup().apply {
            actionManager.getAction("Typst.RecompileFromOutput")?.let { add(it) }
            addSeparator()
            actionManager.getAction("Typst.ClearOutputConsole")?.let { add(it) }
            actionManager.getAction("Typst.ScrollOutputToEnd")?.let { add(it) }
        }
        // horizontal = false → toolbar renders as a vertical column of icons.
        val toolbar = actionManager.createActionToolbar(TOOLBAR_PLACE, actionGroup, false)
        // targetComponent ties action update() context to the console — without this,
        // actions get their update() called with a null project on toolbar repaint and
        // appear permanently disabled. ConsoleViewImpl is itself a UiCompatibleDataProvider
        // that publishes the ConsoleView under LangDataKeys.CONSOLE_VIEW, so the
        // Clear / Scroll-to-end actions retrieve it from e.getData(...) for free.
        toolbar.targetComponent = console.component

        // vertical = true → toolbar is placed on the left side of the content area
        // (the matching idiom IntelliJ's own tool windows use). borderless = true keeps
        // the visual flush with the tool window's chrome.
        val panel = SimpleToolWindowPanel(true, true).apply {
            setToolbar(toolbar.component)
            setContent(console.component)
        }

        val content = toolWindow.contentManager.factory.createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)

        // Disposal wiring, half 2 of 2 — see disposableConsole above. The content owns
        // the console: removing the content disposes the console, which releases the
        // underlying EditorImpl and the message filter registered on it, and that in
        // turn fires the disposable above to clear the holder slot.
        Disposer.register(content, console)
    }

    companion object {
        // ActionManager uses the place string for telemetry / shortcut routing.
        // Keep it stable so any future "where was this action invoked from" lookup
        // (in logs, action listeners) resolves to a meaningful name.
        private const val TOOLBAR_PLACE = "TypstOutputToolWindow"
    }
}

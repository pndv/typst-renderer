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
        // editor. All three output paths — TypstCompileService, TypstWatchService,
        // TypstFilePreviewer — write into this shared console, so a single filter
        // registration covers every code path that emits typst diagnostics.
        console.addMessageFilter(TypstConsoleFilter(project))

        val consoleHolder = project.service<TypstConsoleHolder>()
        consoleHolder.console = console

        // Clear the consoleHolder's console reference when the console is disposed
        val disposableConsole = Disposable { consoleHolder.console = null }
        Disposer.register(console, disposableConsole)

        // Toolbar Actions Layout: vertical strip down the left edge of the
        // tool window, matching the native convention used by Run / Debug / Build / Terminal.
        //
        // Order: [Recompile] [Watch] | [Clear] [Scroll-to-end]
        //
        // All four actions are registered in plugin.xml (so their text / description come
        // from TypstBundle and are localisable, and they're discoverable from the action
        // search palette + assignable to keyboard shortcuts) and pulled by ID here.
        //
        // Recompile / Watch resolve their target file via TypstLastCompiledTracker so they
        // work even when the user has the tool window focused with no .typ editor active.
        //
        // Clear / Scroll-to-end resolve their target console via LangDataKeys.CONSOLE_VIEW,
        // which ConsoleViewImpl.uiDataSnapshot populates automatically — combined with
        // toolbar.targetComponent = console.component below, the data context just works.
        val actionManager = ActionManager.getInstance()
        val actionGroup = DefaultActionGroup().apply {
            actionManager.getAction("Typst.RecompileFromOutput")?.let { add(it) }
            actionManager.getAction("Typst.WatchToggleFromOutput")?.let { add(it) }
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

        // Tie the console's reference to the content's lifecycle
        Disposer.register(content, console)
    }

    companion object {
        // ActionManager uses the place string for telemetry / shortcut routing.
        // Keep it stable so any future "where was this action invoked from" lookup
        // (in logs, action listeners) resolves to a meaningful name.
        private const val TOOLBAR_PLACE = "TypstOutputToolWindow"
    }
}

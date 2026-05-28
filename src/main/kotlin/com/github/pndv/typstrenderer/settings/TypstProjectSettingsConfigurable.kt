package com.github.pndv.typstrenderer.settings

import com.github.pndv.typstrenderer.TypstBundle.message
import com.github.pndv.typstrenderer.lsp.TinymistLspServerSupportProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * Project-scoped settings page (Settings > Tools > Typst > Project Overrides).
 *
 * Surfaces the two per-project overrides held in [TypstProjectSettingsState]:
 *   - `typstProjectRoot` → `--root` (typst CLI) / process cwd (tinymist LSP)
 *   - `typstFontPath`    → `--font-path` (both typst CLI and tinymist LSP)
 *
 * Both are bound at process-spawn time, so applying a change here restarts
 * the tinymist LSP for this project — otherwise the running server keeps the
 * stale argv, and completions / diagnostics drift out of sync with the
 * rendered output. Manual compile/watch invocations always read the current
 * value, so they need no extra wiring.
 */
class TypstProjectSettingsConfigurable(private val project: Project) : Configurable {

    private val log = logger<TypstProjectSettingsConfigurable>()
    private val settings = TypstProjectSettingsState.getInstance(project)
    private var settingsPanel: DialogPanel? = null
    private var rootBeforeApply: String = settings.typstProjectRoot
    private var fontPathBeforeApply: String = settings.typstFontPath

    override fun getDisplayName(): String = message("settings.project.displayName")

    override fun createComponent(): JComponent = panel {
        group(message("settings.project.overrides.group.label")) {
            row(message("settings.project.root.label")) {
                textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.createSingleFolderDescriptor()
                        .withTitle(message("settings.project.root.select.text"))
                ).bindText(settings::typstProjectRoot)
                    .comment(message("settings.project.root.comment"))
            }
            row(message("settings.project.fontPath.label")) {
                textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.createSingleFolderDescriptor()
                        .withTitle(message("settings.project.fontPath.select.text"))
                ).bindText(settings::typstFontPath)
                    .comment(message("settings.project.fontPath.comment"))
            }
        }
    }.also {
        settingsPanel = it
        rootBeforeApply = settings.typstProjectRoot
        fontPathBeforeApply = settings.typstFontPath
    }

    override fun isModified(): Boolean = settingsPanel?.isModified() == true

    override fun apply() {
        settingsPanel?.apply()

        val rootChanged = settings.typstProjectRoot != rootBeforeApply
        val fontPathChanged = settings.typstFontPath != fontPathBeforeApply
        if (rootChanged || fontPathChanged) {
            // tinymist reads --font-path and its working directory at process
            // spawn — bounce the server so the new argv takes effect.
            val lspServerManager = LspServerManager.getInstance(project)
            ApplicationManager.getApplication().executeOnPooledThread {
                log.debug {"Settings changed. Restarting LSP server for project ${project.name}"}
                try {
                    lspServerManager
                        .stopAndRestartIfNeeded(TinymistLspServerSupportProvider::class.java)
                } catch (pce: ProcessCanceledException) {
                    throw pce
                } catch (e: Throwable) {
                    log.warn("Failed to restart LSP server for project ${project.name}: ${e.message}", e)
                } finally {
                    // Update the baselines here, inside the pooled thread, rather than
                    // synchronously after dispatching. Updating them on the EDT before
                    // the restart runs would mean a rapid second Apply() sees no diff
                    // (rootBeforeApply already equals the new value) and skips its own
                    // restart — leaving the LSP configured with the first change while
                    // the user's intent was the application of the last change.
                    rootBeforeApply = settings.typstProjectRoot
                    fontPathBeforeApply = settings.typstFontPath
                }
            }
        } else {
            // No LSP restart needed — still advance the baselines so the next
            // Apply() compares against the current values, not stale ones.
            rootBeforeApply = settings.typstProjectRoot
            fontPathBeforeApply = settings.typstFontPath
        }
    }

    override fun reset() {
        settingsPanel?.reset()
        rootBeforeApply = settings.typstProjectRoot
        fontPathBeforeApply = settings.typstFontPath
    }
}

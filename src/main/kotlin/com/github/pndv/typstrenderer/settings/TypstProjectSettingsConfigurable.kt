package com.github.pndv.typstrenderer.settings

import com.github.pndv.typstrenderer.TypstBundle.message
import com.github.pndv.typstrenderer.language.TypstFileType
import com.github.pndv.typstrenderer.lsp.TinymistLspServerSupportProvider
import com.github.pndv.typstrenderer.lsp.TypstMainFilePinner
import com.github.pndv.typstrenderer.lsp.ensureExternalLspForOpenFiles
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit
import javax.swing.JComponent

/**
 * Project-scoped settings page (Settings > Tools > Typst > Project Overrides).
 *
 * Surfaces the per-project overrides held in [TypstProjectSettingsState]:
 *   - `typstProjectRoot` → `--root` (typst CLI) / process cwd (tinymist LSP)
 *   - `typstFontPath`    → `--font-path` (both typst CLI and tinymist LSP)
 *   - `typstExportPath`  → tinymist `outputPath` template (export directory)
 *
 * All are bound at process-spawn or LSP-init time, so applying a change here restarts
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
    private var exportPathBeforeApply: String = settings.typstExportPath
    private var mainFileBeforeApply: String = settings.typstMainFile

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
            row(message("settings.project.exportPath.label")) { // Plain text field, not a folder picker: the value is a directory
                // *relative* to the project root, not an absolute filesystem path.
                textField().bindText(settings::typstExportPath).comment(message("settings.project.exportPath.comment"))
            }
            row(message("settings.project.mainFile.label")) {
                textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.createSingleFileDescriptor(TypstFileType)
                        .withTitle(message("settings.project.mainFile.select.text"))
                ).bindText(settings::typstMainFile).comment(message("settings.project.mainFile.comment"))
            }
        }
    }.also {
        settingsPanel = it
        rootBeforeApply = settings.typstProjectRoot
        fontPathBeforeApply = settings.typstFontPath
        exportPathBeforeApply = settings.typstExportPath
        mainFileBeforeApply = settings.typstMainFile
    }

    override fun isModified(): Boolean = settingsPanel?.isModified() == true

    override fun apply() {
        settingsPanel?.apply()

        val rootChanged = settings.typstProjectRoot != rootBeforeApply
        val fontPathChanged = settings.typstFontPath != fontPathBeforeApply
        val exportPathChanged = settings.typstExportPath != exportPathBeforeApply
        val mainFileChanged = settings.typstMainFile != mainFileBeforeApply
        if (rootChanged || fontPathChanged || exportPathChanged) { // tinymist reads --font-path and its working directory at process spawn,
            // and the outputPath template at LSP init — bounce the server so the new
            // argv and initializationOptions take effect. The restart drives the server back
            // to Running, and TypstMainFilePinServerListener re-sends (or omits) the pin then.
            val lspClientManager = LspClientManager.getInstance(project)
            ApplicationManager.getApplication().executeOnPooledThread {
                log.debug {"Settings changed. Restarting LSP server for project ${project.name}"}
                try {
                    lspClientManager.stopAndRestartClientsIfNeeded(TinymistLspServerSupportProvider::class.java)

                    // stopAndRestartClientsIfNeeded is provider-scoped: it stops the external-file
                    // clients too, but the platform can only restart what it derives from
                    // LspIntegrationProvider.fileOpened — which it never calls for out-of-content
                    // files. Without this, one Apply() orphans every out-of-project .typ until its
                    // editor is closed and reopened. Deferred rather than immediate because the
                    // stop is still in flight here: ensureClientStarted would otherwise dedupe
                    // against the dying client and hand it straight back.
                    AppExecutorUtil.getAppScheduledExecutorService().schedule(
                        { ensureExternalLspForOpenFiles(project) },
                        EXTERNAL_LSP_REARM_DELAY_MS,
                        TimeUnit.MILLISECONDS,
                    )

                    // A main-file change applied in the same Apply() as a restart-triggering
                    // setting still needs the preview broadcast: the attach replay re-sends the
                    // pin but deliberately stays silent, and an idle pane that has already
                    // rendered has no other reason to re-export — it would keep a PDF built
                    // under the old pin state.
                    if (mainFileChanged) {
                        TypstMainFilePinner.applyConfiguredPin(project)
                    }
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
                    exportPathBeforeApply = settings.typstExportPath
                    mainFileBeforeApply = settings.typstMainFile
                }
            }
        } else { // No LSP restart needed. The main file feeds a runtime executeCommand
            // (tinymist.pinMain), not the process argv or init options, so apply it
            // live against the running server instead of bouncing it: pin when a valid
            // entry is configured, unpin when the value was cleared.
            // applyConfiguredPin covers both directions — it pins the configured file, or
            // sends pinMain(null) when the value was cleared — and broadcasts either way so
            // open previews re-export against the new target.
            if (mainFileChanged) {
                TypstMainFilePinner.applyConfiguredPin(project)
            } // Still advance the baselines so the next Apply() compares against the
            // current values, not stale ones.
            rootBeforeApply = settings.typstProjectRoot
            fontPathBeforeApply = settings.typstFontPath
            exportPathBeforeApply = settings.typstExportPath
            mainFileBeforeApply = settings.typstMainFile
        }
    }

    override fun reset() {
        settingsPanel?.reset()
        rootBeforeApply = settings.typstProjectRoot
        fontPathBeforeApply = settings.typstFontPath
        exportPathBeforeApply = settings.typstExportPath
        mainFileBeforeApply = settings.typstMainFile
    }

    private companion object {
        /**
         * How long to wait after `stopAndRestartClientsIfNeeded` before re-establishing
         * external-file clients. The stop is asynchronous, so re-arming immediately would dedupe
         * against a client that is about to terminate.
         */
        const val EXTERNAL_LSP_REARM_DELAY_MS = 1_500L
    }
}

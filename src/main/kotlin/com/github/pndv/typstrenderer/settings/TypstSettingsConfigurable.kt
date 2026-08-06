package com.github.pndv.typstrenderer.settings

import com.github.pndv.typstrenderer.TypstBundle.message
import com.github.pndv.typstrenderer.editor.TypstPreviewMode
import com.github.pndv.typstrenderer.lsp.TinymistDownloadService
import com.github.pndv.typstrenderer.lsp.TinymistManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import javax.swing.JComponent

class TypstSettingsConfigurable : Configurable {

    private val settings = TypstSettingsState.getInstance()
    private var tinymistStatusLabel: JBLabel? = null

    private var settingsPanel: DialogPanel? = null

    override fun getDisplayName(): String = message("settings.displayName")

    override fun createComponent(): JComponent = panel {
        group(message("settings.lsp.group.label")) {
            row(message("settings.lsp.status.text")) {
                tinymistStatusLabel = JBLabel(getTinymistStatusText()).also { cell(it) }
            }
            row(message("settings.lsp.path.label")) {
                textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.singleFile().withTitle(message("settings.lsp.path.text"))
                ).bindText(settings::tinymistPath).comment(message("settings.lsp.path.comment"))
            }
            row {
                button(message("settings.lsp.download.label")) {
                    tinymistStatusLabel?.text = message("settings.lsp.download.text")
                    TinymistDownloadService.getInstance().downloadInBackground(null) { success ->
                        tinymistStatusLabel?.text =
                            if (success) getTinymistStatusText() else message("settings.lsp.download.failed.text")
                    }
                }.comment(message("settings.lsp.download.comment"))
            }
        }

        group(message("settings.preview.group.label")) {
            row(message("settings.preview.mode.label")) {
                comboBox(
                    TypstPreviewMode.entries,
                    previewModeRenderer()
                ).comment(message("settings.preview.mode.comment"))
                    .bindItem(settings::defaultPreviewMode.toNullableProperty(TypstPreviewMode.LIVE))
            }
            row {
                checkBox(message("settings.preview.onType.label")).comment(message("settings.preview.onType.comment"))
                    .bindSelected(settings::livePreviewOnType)
            }
            row {
                checkBox(message("settings.preview.followCursor.label")).comment(message("settings.preview.followCursor.comment"))
                    .bindSelected(settings::livePreviewFollowCursor)
            }
            row {
                checkBox(message("settings.preview.checkbox.label")).comment(message("settings.preview.checkbox.comment"))
                    .bindSelected(settings::rememberPreviewScrollAcrossRestart)
            }
        }
    }.also { settingsPanel = it }

    /**
     * Renders the mode constants with their user-facing names. Without a renderer the combo
     * would show `LIVE` / `PDF` — the Kotlin constant names rather than translatable labels.
     */
    private fun previewModeRenderer() = textListCellRenderer("") { mode: TypstPreviewMode ->
        message(
            when (mode) {
                TypstPreviewMode.LIVE -> "settings.preview.mode.live"
                TypstPreviewMode.PDF -> "settings.preview.mode.pdf"
            }
        )
    }

    override fun isModified(): Boolean = settingsPanel?.isModified() == true

    override fun apply() {
        settingsPanel?.apply() // Refresh status labels after applying new paths
        tinymistStatusLabel?.text = getTinymistStatusText()
    }

    override fun reset() {
        settingsPanel?.reset()
        tinymistStatusLabel?.text = getTinymistStatusText()
    }

    private fun getTinymistStatusText(): String {
        val manager = TinymistManager.getInstance()
        val resolvedPath = manager.resolveTinymistPath()
        return if (resolvedPath != null) {
            message("settings.lsp.binary.found.text", resolvedPath)
        } else {
            message("settings.lsp.binary.notFound.text")
        }
    }
}

package com.github.pndv.typstrenderer.settings

import com.github.pndv.typstrenderer.TypstBundle.message
import com.github.pndv.typstrenderer.lsp.TinymistDownloadService
import com.github.pndv.typstrenderer.lsp.TinymistManager
import com.github.pndv.typstrenderer.lsp.TypstDownloadService
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class TypstSettingsConfigurable : Configurable {

    private val settings = TypstSettingsState.getInstance()
    private var tinymistPath = settings.tinymistPath
    private var typstPath = settings.typstPath
    private var autoCompileOnSave = settings.autoCompileOnSave
    private var rememberPreviewScrollAcrossRestart = settings.rememberPreviewScrollAcrossRestart
    private var tinymistStatusLabel: JBLabel? = null
    private var typstStatusLabel: JBLabel? = null

    override fun getDisplayName(): String = message("settings.displayName")

    override fun createComponent(): JComponent = panel {
        group(message("settings.lsp.group.label")) {
            row(message("settings.lsp.status.text")) {
                tinymistStatusLabel = JBLabel(getTinymistStatusText()).also { cell(it) }
            }
            row(message("settings.lsp.path.label")) {
                textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.singleFile()
                        .withTitle(message("settings.lsp.path.text"))
                ).bindText(::tinymistPath)
                    .comment(message("settings.lsp.path.comment"))
            }
            row {
                button(message("settings.lsp.download.label")) {
                    tinymistStatusLabel?.text = message("settings.lsp.download.text")
                    TinymistDownloadService.getInstance().downloadInBackground(null) { success ->
                        tinymistStatusLabel?.text = if (success) getTinymistStatusText() else message("settings.lsp.download.failed.text")
                    }
                }.comment(message("settings.lsp.download.comment"))
            }
        }

        group(message("settings.compiler.group.label")) {
            row(message("settings.compiler.status.text")) {
                typstStatusLabel = JBLabel(getTypstStatusText()).also { cell(it) }
            }
            row(message("settings.compiler.path.label")) {
                textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.singleFile().withTitle(message("settings.compiler.path.select.text"))
                ).comment(message("settings.compiler.path.comment"))
                    .bindText(::typstPath)
            }
            row {
                button(message("settings.compiler.download.button.label")) {
                    typstStatusLabel?.text = message("settings.compiler.download.text")
                    TypstDownloadService.getInstance().downloadInBackground(null) {
                        success ->
                        typstStatusLabel?.text = if (success) getTypstStatusText() else message("settings.compiler.download.failed.text")
                    }
                }.comment(message("settings.compiler.download.comment"))
            }
            row {
                checkBox(message("settings.compiler.checkbox.autoCompile"))
                    .bindSelected(::autoCompileOnSave)
            }
        }

        group(message("settings.preview.group.label")) {
            row {
                checkBox(message("settings.preview.checkbox.label"))
                    .comment(message("settings.preview.checkbox.comment"))
                    .bindSelected(::rememberPreviewScrollAcrossRestart)
            }
        }
    }

    override fun isModified(): Boolean =
        tinymistPath != settings.tinymistPath ||
                typstPath != settings.typstPath ||
                autoCompileOnSave != settings.autoCompileOnSave ||
                rememberPreviewScrollAcrossRestart != settings.rememberPreviewScrollAcrossRestart

    override fun apply() {
        settings.tinymistPath = tinymistPath
        settings.typstPath = typstPath
        settings.autoCompileOnSave = autoCompileOnSave
        settings.rememberPreviewScrollAcrossRestart = rememberPreviewScrollAcrossRestart
        // Refresh status labels after applying new paths
        tinymistStatusLabel?.text = getTinymistStatusText()
        typstStatusLabel?.text = getTypstStatusText()
    }

    override fun reset() {
        tinymistPath = settings.tinymistPath
        typstPath = settings.typstPath
        autoCompileOnSave = settings.autoCompileOnSave
        rememberPreviewScrollAcrossRestart = settings.rememberPreviewScrollAcrossRestart
        tinymistStatusLabel?.text = getTinymistStatusText()
        typstStatusLabel?.text = getTypstStatusText()
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

    private fun getTypstStatusText(): String {
        val manager = TinymistManager.getInstance()
        val resolvedPath = manager.resolveTypstPath()
        return if (resolvedPath != null) {
            message("settings.compiler.binary.found.text", resolvedPath)
        } else {
            message("settings.compiler.binary.notFound.text")
        }
    }
}

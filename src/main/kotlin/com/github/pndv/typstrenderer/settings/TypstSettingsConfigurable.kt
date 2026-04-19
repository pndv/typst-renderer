package com.github.pndv.typstrenderer.settings

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

    override fun getDisplayName(): String = "Typst"

    override fun createComponent(): JComponent = panel {
        group("Language Server (Tinymist)") {
            row("Status:") {
                tinymistStatusLabel = JBLabel(getTinymistStatusText()).also { cell(it) }
            }
            row("Tinymist path:") {
                textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.singleFile()
                        .withTitle("Select Tinymist Binary")
                ).bindText(::tinymistPath)
                    .comment("Path to the tinymist binary. Leave empty for auto-detection.")
            }
            row {
                button("Download Tinymist") {
                    tinymistStatusLabel?.text = "Downloading..."
                    TinymistDownloadService.getInstance().downloadInBackground(null) { success ->
                        tinymistStatusLabel?.text = if (success) getTinymistStatusText() else "Download failed"
                    }
                }.comment("Downloads the latest tinymist binary from GitHub for this platform.")
            }
        }
        group("Compilation (Typst CLI)") {
            row("Status:") {
                typstStatusLabel = JBLabel(getTypstStatusText()).also { cell(it) }
            }
            row("Typst CLI path:") {
                textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.singleFile()
                        .withTitle("Select Typst Binary")
                ).bindText(::typstPath)
                    .comment("Path to the typst CLI binary. Leave empty for auto-detection.")
            }
            row {
                button("Download Typst") {
                    typstStatusLabel?.text = "Downloading..."
                    TypstDownloadService.getInstance().downloadInBackground(null) { success ->
                        typstStatusLabel?.text = if (success) getTypstStatusText() else "Download failed"
                    }
                }.comment("Downloads the latest Typst CLI binary from GitHub for this platform.")
            }
            row {
                checkBox("Auto-compile on save")
                    .bindSelected(::autoCompileOnSave)
            }
        }
        group("Preview") {
            row {
                checkBox("Remember PDF preview scroll position across editor restarts")
                    .bindSelected(::rememberPreviewScrollAcrossRestart)
                    .comment(
                        "When enabled, reopening a .typ file restores the preview to the page and " +
                                "scroll offset you were viewing. Scroll position is always preserved " +
                                "across recompilation within a session regardless of this setting."
                    )
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
            "✓ Found: $resolvedPath"
        } else {
            "✗ Not found (will auto-download when a .typ file is opened)"
        }
    }

    private fun getTypstStatusText(): String {
        val manager = TinymistManager.getInstance()
        val resolvedPath = manager.resolveTypstPath()
        return if (resolvedPath != null) {
            "✓ Found: $resolvedPath"
        } else {
            "✗ Not found (will auto-download when needed)"
        }
    }
}

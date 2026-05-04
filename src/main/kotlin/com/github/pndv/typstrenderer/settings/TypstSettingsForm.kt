package com.github.pndv.typstrenderer.settings


import com.intellij.openapi.observable.properties.PropertyGraph
import com.github.pndv.typstrenderer.lsp.TinymistDownloadService
import com.github.pndv.typstrenderer.lsp.TinymistManager
import com.github.pndv.typstrenderer.lsp.TypstDownloadService
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import java.awt.BorderLayout
import javax.swing.JPanel

class TypstSettingsForm : JPanel()  {
    private val settings
        get() = TypstSettings.getInstance()

    private val properties = PropertyGraph()

    val tinymistPath = properties.property(settings.tinymistPath)
    val typstPath = properties.property(settings.typstPath)
    val autoCompileOnSave = properties.property(settings.autoCompileOnSave)
    val rememberPreviewScrollAcrossRestart = properties.property(settings.rememberPreviewScrollAcrossRestart)
    var tinymistStatusLabel: JBLabel? = null
    var typstStatusLabel: JBLabel? = null

    private val generalSettingsGroup = panel {
        group("Language Server (Tinymist)") {
            row("Status:") {
                tinymistStatusLabel = JBLabel(getTinymistStatusText()).also { cell(it) }
            }
            row("Tinymist path:") {
                textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.singleFile()
                        .withTitle("Select Tinymist Binary")
                ).bindText(tinymistPath)
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
                ).bindText(typstPath).comment("Path to the typst CLI binary. Leave empty for auto-detection.")
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
                    .bindSelected(autoCompileOnSave)
            }
        }
        group("Preview") {
            row {
                checkBox("Remember PDF preview scroll position across editor restarts")
                    .bindSelected(rememberPreviewScrollAcrossRestart)
                    .comment(
                        "When enabled, reopening a .typ file restores the preview to the page and " +
                                "scroll offset you were viewing. Scroll position is always preserved " +
                                "across recompilation within a session regardless of this setting."
                    )
            }
        }
    }

    init {
        layout = BorderLayout()
        add(panel {
            row { cell(generalSettingsGroup).align(AlignX.FILL) }
        })
    }

    fun reset() {
        tinymistPath.set(settings.tinymistPath)
        typstPath.set(settings.typstPath)
        autoCompileOnSave.set(settings.autoCompileOnSave)
        rememberPreviewScrollAcrossRestart.set(settings.rememberPreviewScrollAcrossRestart)
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
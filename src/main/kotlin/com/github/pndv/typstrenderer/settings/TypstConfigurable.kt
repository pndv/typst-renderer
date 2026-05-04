package com.github.pndv.typstrenderer.settings

import com.intellij.openapi.options.Configurable
import javax.swing.JComponent

class TypstConfigurable : Configurable {
    private var settingsForm: TypstSettingsForm? = null

    private val settings = TypstSettings.getInstance()

    override fun getDisplayName(): String = "Typst"

    override fun createComponent(): JComponent? {
        settingsForm = settingsForm ?: TypstSettingsForm()
        return settingsForm
    }

    override fun isModified(): Boolean {
        return settingsForm?.run {
            settings.tinymistPath != tinymistPath.get() ||
                    settings.typstPath != typstPath.get() ||
                    settings.autoCompileOnSave != autoCompileOnSave.get() ||
                    settings.rememberPreviewScrollAcrossRestart != rememberPreviewScrollAcrossRestart.get()
        } ?: false
    }

    override fun apply() {
        settings.run {
            tinymistPath = settingsForm?.tinymistPath?.get() ?: tinymistPath
            typstPath = settingsForm?.typstPath?.get() ?: typstPath
            autoCompileOnSave = settingsForm?.autoCompileOnSave?.get() ?: autoCompileOnSave
            rememberPreviewScrollAcrossRestart =
                settingsForm?.rememberPreviewScrollAcrossRestart?.get() ?: rememberPreviewScrollAcrossRestart
        }
    }

    override fun reset() {
        settingsForm?.reset()
    }

    override fun disposeUIResources() {
        settingsForm = null
    }
}

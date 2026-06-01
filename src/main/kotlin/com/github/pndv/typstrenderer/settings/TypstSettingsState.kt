package com.github.pndv.typstrenderer.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.XmlSerializerUtil.copyBean

@Service(Service.Level.APP)
@State(name = "TypstSettings", storages = [Storage("TypstSettings.xml")])
class TypstSettingsState : PersistentStateComponent<TypstSettingsState.State> {

    data class State(
        var tinymistPath: String = "",
        var typstPath: String = "",
        var autoCompileOnSave: Boolean = false,
        var rememberPreviewScrollAcrossRestart: Boolean = false
    )

    private var state = State()

    var tinymistPath: String
        get() = state.tinymistPath
        set(value) { state.tinymistPath = value }

    var typstPath: String
        get() = state.typstPath
        set(value) { state.typstPath = value }

    var autoCompileOnSave: Boolean
        get() = state.autoCompileOnSave
        set(value) { state.autoCompileOnSave = value }

    var rememberPreviewScrollAcrossRestart: Boolean
        get() = state.rememberPreviewScrollAcrossRestart
        set(value) { state.rememberPreviewScrollAcrossRestart = value }

    override fun getState(): State = state

    override fun loadState(state: State) {
        // Mutate the existing state in place rather than swapping the reference.
        // The XML serialization machinery tracks the field's identity; reassigning
        // it (the original `this.state = state`) caused IntelliJ to lose track and
        // silently fail to persist user changes after a settings reload.
        copyBean(state, this.state)
    }

    companion object {
        fun getInstance(): TypstSettingsState = service()
    }
}

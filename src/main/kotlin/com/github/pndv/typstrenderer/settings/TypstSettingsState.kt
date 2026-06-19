package com.github.pndv.typstrenderer.settings

import com.intellij.openapi.components.*
import com.intellij.util.xmlb.XmlSerializerUtil.copyBean

@Service(Service.Level.APP)
@State(name = "TypstSettings", storages = [Storage("TypstSettings.xml")])
class TypstSettingsState : PersistentStateComponent<TypstSettingsState.State> {

    data class State(
        var tinymistPath: String = "",
        var rememberPreviewScrollAcrossRestart: Boolean = false
    )

    private var state = State()

    var tinymistPath: String
        get() = state.tinymistPath
        set(value) { state.tinymistPath = value }

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

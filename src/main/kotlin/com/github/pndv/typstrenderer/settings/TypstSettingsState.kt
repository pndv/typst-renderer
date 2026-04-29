package com.github.pndv.typstrenderer.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

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
        this.state = state
    }

    companion object {
        fun getInstance(): TypstSettingsState =
            ApplicationManager.getApplication().getService(TypstSettingsState::class.java)
    }
}

package com.github.pndv.typstrenderer.settings

import com.github.pndv.typstrenderer.editor.TypstPreviewMode
import com.intellij.openapi.components.*
import com.intellij.util.xmlb.XmlSerializerUtil.copyBean

@Service(Service.Level.APP)
@State(name = "TypstSettings", storages = [Storage("TypstSettings.xml")])
class TypstSettingsState : PersistentStateComponent<TypstSettingsState.State> {

    data class State(
        var tinymistPath: String = "",
        var rememberPreviewScrollAcrossRestart: Boolean = false, // Stored as the enum's id rather than the enum itself: an unrecognised value
        // (downgrade, hand-edited XML) then degrades to the default instead of failing
        // to deserialise the whole settings object.
        var defaultPreviewMode: String = TypstPreviewMode.LIVE.id,
        var livePreviewOnType: Boolean = true,
        var livePreviewFollowCursor: Boolean = true
    )

    private var state = State()

    var tinymistPath: String
        get() = state.tinymistPath
        set(value) { state.tinymistPath = value }

    var rememberPreviewScrollAcrossRestart: Boolean
        get() = state.rememberPreviewScrollAcrossRestart
        set(value) { state.rememberPreviewScrollAcrossRestart = value }

    /** Mode a newly opened preview pane starts in. The pane's own toggle overrides it per editor. */
    var defaultPreviewMode: TypstPreviewMode
        get() = TypstPreviewMode.fromId(state.defaultPreviewMode)
        set(value) {
            state.defaultPreviewMode = value.id
        }

    /**
     * Whether the live preview re-renders on every keystroke (`true`) or only on save.
     * On-save exists for very large documents where continuous recompilation costs more
     * than the immediacy is worth.
     */
    var livePreviewOnType: Boolean
        get() = state.livePreviewOnType
        set(value) {
            state.livePreviewOnType = value
        }

    /**
     * Whether the live preview scrolls to follow the editor caret. Off leaves the preview where
     * the reader put it, at the cost of a newly opened tab starting at the top of the document.
     */
    var livePreviewFollowCursor: Boolean
        get() = state.livePreviewFollowCursor
        set(value) {
            state.livePreviewFollowCursor = value
        }

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

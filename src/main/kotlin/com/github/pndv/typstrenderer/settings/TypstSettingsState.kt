package com.github.pndv.typstrenderer.settings

import com.github.pndv.typstrenderer.editor.TypstPreviewMode
import com.github.pndv.typstrenderer.lsp.WslExecutionMode
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
        var livePreviewFollowCursor: Boolean = true,

        // Whether tinymist runs inside a WSL distribution rather than natively — see
        // WslExecution.kt. Stored as the enum's name (same unrecognised-value-degrades-to-default
        // rationale as defaultPreviewMode above).
        var wslMode: String = WslExecutionMode.AUTO.name,

        // Explicit WSL distribution override. Read by WslExecutionMode.ALWAYS unconditionally, and
        // by AUTO to pick which distro to use when one was already detected from the project path.
        var wslDistro: String = "",

        // Manual fallback path to the tinymist binary inside the WSL distribution, used when
        // `which tinymist` inside the distro does not find it.
        var tinymistWslPath: String = "",
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

    /** How tinymist decides whether to run inside WSL — see [WslExecutionMode]. */
    var wslMode: WslExecutionMode
        get() = WslExecutionMode.fromStoredValue(state.wslMode)
        set(value) {
            state.wslMode = value.name
        }

    var wslDistro: String
        get() = state.wslDistro
        set(value) { state.wslDistro = value }

    var tinymistWslPath: String
        get() = state.tinymistWslPath
        set(value) { state.tinymistWslPath = value }

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

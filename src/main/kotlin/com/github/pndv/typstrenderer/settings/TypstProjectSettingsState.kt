package com.github.pndv.typstrenderer.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil.copyBean

/**
 * Project-scoped settings for overrides that depend on per-project context
 * (e.g. the Typst project root when it diverges from `project.basePath`).
 *
 * Application-level defaults live in [TypstSettingsState]; this `state` is
 * intentionally separate so different IntelliJ projects can hold different
 * overrides simultaneously. The first entry is the project-root override;
 * the font-path override and any future per-project setting.
 */
@Service(Service.Level.PROJECT)
@State(name = "TypstProjectSettings", storages = [Storage("TypstProjectSettings.xml")])
class TypstProjectSettingsState : PersistentStateComponent<TypstProjectSettingsState.State> {

    data class State(
        // The `--root` argument to `typst` cli/tinymist.
        var typstProjectRoot: String = "",

        // The `--font-path` argument to `typst` cli/tinymist.
        var typstFontPath: String = "",

        // Directory, relative to the project root, where tinymist writes exported
        // PDFs; the source folder structure is mirrored beneath it. Feeds the
        // `outputPath` template sent to tinymist (see TinymistLspServerDescriptor).
        var typstExportPath: String = DEFAULT_EXPORT_PATH,

        // Absolute path of the file pinned as the compilation entry (main.typ) for a
        // multi-file project. Re-sent to tinymist via `tinymist.pinMain` on every
        // LSP attach so cross-file references resolve when a chapter file is opened
        // in isolation. Blank means no pin — tinymist compiles the focused file.
        var typstMainFile: String = "",
    )

    private var state = State()

    var typstProjectRoot: String
        get() = state.typstProjectRoot
        set(value) { state.typstProjectRoot = value }

    var typstFontPath: String
        get() = state.typstFontPath
        set(value) { state.typstFontPath = value }

    var typstExportPath: String
        get() = state.typstExportPath
        set(value) {
            state.typstExportPath = value
        }

    var typstMainFile: String
        get() = state.typstMainFile
        set(value) {
            state.typstMainFile = value
        }

    override fun getState(): State = state

    override fun loadState(state: State) {
        // Mutate in place rather than swapping the reference. Same persistence-bug
        // gotcha as [TypstSettingsState] — reassigning the field loses the
        // serializer's tracked identity and silently breaks settings persistence
        // (shipped twice in 0.1.0/0.1.1 before the 0.1.2 fix).
        copyBean(state, this.state)
    }

    companion object {
        /** Default export directory, relative to the project root. */
        const val DEFAULT_EXPORT_PATH = "target"

        fun getInstance(project: Project): TypstProjectSettingsState = project.service()
    }
}

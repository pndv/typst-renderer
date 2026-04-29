package com.github.pndv.typstrenderer.editor

import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel

/**
 * Viewport state captured from Chromium's PDF viewer: current page (1-based)
 * and vertical scroll offset within that page (CSS px, as reported by the viewer).
 *
 * Serialized to a URL hash fragment that Chromium's PDF viewer parses on load.
 */
internal data class PdfViewportState(
    val page: Int,
    val yOffset: Double,
) {
    /** Chromium PDF viewer fragment: `#page=N&zoom=Z,X,Y` — `Z=100` means 100% (one page width). */
    fun toUrlFragment(): String = "#page=$page&zoom=100,0,${yOffset.toInt()}"

    companion object {
        private val PAGE_REGEX = Regex("\"page\"\\s*:\\s*(\\d+)")
        private val Y_REGEX = Regex("\"yOffset\"\\s*:\\s*(-?[0-9.eE+]+)")

        /** Parses `{"page":N,"yOffset":D}`. Returns null on any malformed input or diagnostic payloads. */
        fun fromJson(json: String?): PdfViewportState? {
            if (json.isNullOrBlank()) return null
            // Diagnostic payloads are marked with __diag=true and must not be parsed as viewport state.
            if (json.contains("\"__diag\"")) return null
            val page = PAGE_REGEX.find(json)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val y = Y_REGEX.find(json)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            if (page < 1) return null
            return PdfViewportState(page, y)
        }
    }
}

/** FileEditorState wrapper so IntelliJ persists the viewport to `.idea/workspace.xml`. */
internal data class PdfViewportFileEditorState(
    val page: Int = 1,
    val yOffset: Double = 0.0,
) : FileEditorState {
    override fun canBeMergedWith(other: FileEditorState, level: FileEditorStateLevel): Boolean =
        other is PdfViewportFileEditorState

    fun toViewport(): PdfViewportState? = if (page >= 1) PdfViewportState(page, yOffset) else null

    companion object {
        fun from(viewport: PdfViewportState): PdfViewportFileEditorState =
            PdfViewportFileEditorState(viewport.page, viewport.yOffset)
    }
}

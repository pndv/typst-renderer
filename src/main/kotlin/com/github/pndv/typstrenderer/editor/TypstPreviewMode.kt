package com.github.pndv.typstrenderer.editor

/**
 * Which renderer the preview pane is showing.
 *
 * The two modes answer different questions, which is why the pane never switches between them
 * on its own. [LIVE] answers "does my document read right?" — tinymist re-renders from the
 * in-memory buffer as you type, with no save and no PDF on disk. [PDF] answers "is my actual
 * output right?" — PDF.js displays the exported artefact, exactly as a reader would receive it,
 * refreshed when that artefact is rewritten.
 *
 * Compile and Export never change the mode. They write the PDF and report to the console; the
 * pane reloads only when it is already in [PDF] mode.
 */
enum class TypstPreviewMode(val id: String) {
    /** tinymist's own preview server, rendered as SVG, updated on keystroke. */
    LIVE("live"),

    /** The vendored PDF.js viewer, showing the exported PDF. */
    PDF("pdf");

    fun toggled(): TypstPreviewMode = if (this == LIVE) PDF else LIVE

    companion object {
        /** Parses a persisted id, falling back to [LIVE] for anything unrecognised. */
        fun fromId(id: String?): TypstPreviewMode = entries.firstOrNull { it.id == id } ?: LIVE
    }
}

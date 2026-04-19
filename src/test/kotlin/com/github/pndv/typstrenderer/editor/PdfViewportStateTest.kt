package com.github.pndv.typstrenderer.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the pure-logic helpers on [PdfViewportState] — URL fragment emission and JSON parsing.
 * These are the serialization boundaries between Kotlin and Chromium's PDF viewer (fragment) and
 * between the JS capture listener and Kotlin (JSON).
 */
class PdfViewportStateTest {

    // ---- toUrlFragment ----

    @Test
    fun toUrlFragment_page1TopOfPage_producesZeroOffset() {
        val state = PdfViewportState(page = 1, yOffset = 0.0)
        assertEquals("#page=1&zoom=100,0,0", state.toUrlFragment())
    }

    @Test
    fun toUrlFragment_pageNWithOffset_truncatesToInteger() {
        val state = PdfViewportState(page = 5, yOffset = 847.73)
        // yOffset is truncated to Int to match Chromium PDF viewer's integer pixel semantics
        assertEquals("#page=5&zoom=100,0,847", state.toUrlFragment())
    }

    // ---- fromJson ----

    @Test
    fun fromJson_wellFormed_returnsState() {
        val parsed = PdfViewportState.fromJson("""{"page":3,"yOffset":120.5}""")
        assertEquals(PdfViewportState(3, 120.5), parsed)
    }

    @Test
    fun fromJson_withExtraFields_stillParses() {
        // The JS capture listener may include viewer-internal fields; parser should ignore them.
        val parsed = PdfViewportState.fromJson(
            """{"page":2,"yOffset":50.0,"type":"viewport","zoom":1.5}"""
        )
        assertEquals(PdfViewportState(2, 50.0), parsed)
    }

    @Test
    fun fromJson_missingYOffset_defaultsToZero() {
        val parsed = PdfViewportState.fromJson("""{"page":7}""")
        assertEquals(PdfViewportState(7, 0.0), parsed)
    }

    @Test
    fun fromJson_null_returnsNull() {
        assertNull(PdfViewportState.fromJson(null))
    }

    @Test
    fun fromJson_empty_returnsNull() {
        assertNull(PdfViewportState.fromJson(""))
    }

    @Test
    fun fromJson_missingPage_returnsNull() {
        assertNull(PdfViewportState.fromJson("""{"yOffset":100}"""))
    }

    @Test
    fun fromJson_pageZero_returnsNull() {
        // Page numbers are 1-based; a zero page is invalid.
        assertNull(PdfViewportState.fromJson("""{"page":0,"yOffset":0}"""))
    }

    @Test
    fun fromJson_garbage_returnsNull() {
        assertNull(PdfViewportState.fromJson("not json at all"))
    }

    @Test
    fun fromJson_diagnosticPayload_returnsNull() {
        // Diagnostic payloads (marked with __diag:true) must not be parsed as viewport state,
        // even if they happen to contain a "page" field.
        val diag = """{"__diag":true,"source":"dom-scan","currentPage":3}"""
        assertNull(PdfViewportState.fromJson(diag))
    }

    // ---- PdfViewportFileEditorState round-trip ----

    @Test
    fun fileEditorState_roundTripsThroughPdfViewportState() {
        val original = PdfViewportState(4, 250.0)
        val editorState = PdfViewportFileEditorState.from(original)
        assertEquals(original, editorState.toViewport())
    }

    @Test
    fun fileEditorState_defaultPageInvalidatesViewport() {
        // A zero page means "not set" — toViewport should return null, not a bogus state.
        val state = PdfViewportFileEditorState(page = 0)
        assertNull(state.toViewport())
    }
}

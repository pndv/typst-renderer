package com.github.pndv.typstrenderer.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the persisted form of the preview-renderer selection.
 *
 * The ids are written into `TypstSettings.xml`, so they are a compatibility surface: renaming
 * one silently resets every user's preference to the default on their next IDE start.
 */
class TypstPreviewModeTest {

    @Test
    fun `ids are the persisted contract and must not drift`() {
        assertEquals("live", TypstPreviewMode.LIVE.id)
        assertEquals("pdf", TypstPreviewMode.PDF.id)
    }

    @Test
    fun `every mode round-trips through its id`() {
        for (mode in TypstPreviewMode.entries) {
            assertEquals(mode, TypstPreviewMode.fromId(mode.id))
        }
    }

    @Test
    fun `an unknown or absent id degrades to live rather than failing`() { // A downgrade, or a hand-edited settings file, must not break deserialisation of
        // the whole settings object — hence a String field plus this fallback.
        assertEquals(TypstPreviewMode.LIVE, TypstPreviewMode.fromId(null))
        assertEquals(TypstPreviewMode.LIVE, TypstPreviewMode.fromId(""))
        assertEquals(TypstPreviewMode.LIVE, TypstPreviewMode.fromId("svg-someday"))
        assertEquals(TypstPreviewMode.LIVE, TypstPreviewMode.fromId("LIVE"))
    }

    @Test
    fun `toggling alternates and is its own inverse`() {
        assertEquals(TypstPreviewMode.PDF, TypstPreviewMode.LIVE.toggled())
        assertEquals(TypstPreviewMode.LIVE, TypstPreviewMode.PDF.toggled())
        for (mode in TypstPreviewMode.entries) {
            assertEquals(mode, mode.toggled().toggled())
        }
    }
}

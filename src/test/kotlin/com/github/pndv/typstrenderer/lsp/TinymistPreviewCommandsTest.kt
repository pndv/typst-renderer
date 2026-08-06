package com.github.pndv.typstrenderer.lsp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path

/**
 * Pure-function tests for the request-building and response-reading sides of
 * [TinymistPreviewCommands].
 *
 * The shapes asserted here were captured from a live tinymist v0.15.2 over a hand-driven
 * JSON-RPC probe. They are easy to get wrong in ways that fail silently — a preview that
 * "just doesn't start" — so each one is pinned: the nested-argv wrapping, the loopback
 * ephemeral-port host, the secondary-task flag, and the numeric widening JSON puts the
 * port through on the way back.
 */
class TinymistPreviewCommandsTest {

    private fun argv(params: org.eclipse.lsp4j.ExecuteCommandParams): List<String> {
        assertEquals("arguments must hold exactly one element", 1, params.arguments.size)
        @Suppress("UNCHECKED_CAST") return params.arguments[0] as List<String>
    }

    private fun startParams(primary: Boolean = true, partialRendering: Boolean = false) =
        TinymistPreviewCommands.buildStartPreviewParams(
            taskId = "tab-1",
            entry = Path.of("main.typ"),
            invertColours = PreviewInvertColours.NEVER,
            refreshStyle = PreviewRefreshStyle.ON_TYPE,
            primary = primary,
            partialRendering = partialRendering,
        )

    @Test
    fun `startPreview carries the exact wire-name`() {
        assertEquals("tinymist.doStartPreview", startParams().command)
    }

    @Test
    fun `startPreview nests an argv list inside the single argument`() { // Not a JSON options object: tinymist re-parses these tokens with the same clap
        // parser the standalone `tinymist preview` subcommand uses.
        val tokens = argv(startParams())
        assertTrue("argv must not be empty", tokens.isNotEmpty())
        assertEquals("--task-id", tokens[0])
        assertEquals("tab-1", tokens[1])
    }

    @Test
    fun `startPreview asks for an OS-assigned loopback port`() {
        val tokens = argv(startParams())
        val idx = tokens.indexOf("--data-plane-host")
        assertTrue("--data-plane-host must be present", idx >= 0)
        assertEquals("127.0.0.1:0", tokens[idx + 1])
    }

    @Test
    fun `startPreview never opens the system browser`() {
        assertTrue("--no-open must be present", argv(startParams()).contains("--no-open"))
    }

    @Test
    fun `startPreview passes the entry last and absolutised`() {
        val tokens = argv(startParams())
        val entry = tokens.last()
        assertTrue("entry must be absolute: $entry", Path.of(entry).isAbsolute)
        assertTrue("entry must preserve filename: $entry", entry.endsWith("main.typ"))
    }

    @Test
    fun `the primary task omits the secondary flag and others carry it`() { // Exactly one live task may be primary; a second start without the flag is
        // rejected with "cannot register preview to the compiler instance".
        assertFalse(argv(startParams(primary = true)).contains("--not-primary"))
        assertTrue(argv(startParams(primary = false)).contains("--not-primary"))
    }

    @Test
    fun `refresh style and colour inversion use tinymist's hyphenated wire values`() {
        assertEquals("on-type", PreviewRefreshStyle.ON_TYPE.wireValue)
        assertEquals("on-save", PreviewRefreshStyle.ON_SAVE.wireValue)
        assertEquals("never", PreviewInvertColours.NEVER.wireValue)
        assertEquals("auto", PreviewInvertColours.AUTO.wireValue)

        val tokens = TinymistPreviewCommands.buildStartPreviewParams(
            taskId = "t", entry = Path.of("a.typ"),
            invertColours = PreviewInvertColours.AUTO,
            refreshStyle = PreviewRefreshStyle.ON_SAVE,
            primary = true, partialRendering = true,
        ).let(::argv)
        assertEquals("auto", tokens[tokens.indexOf("--invert-colors") + 1])
        assertEquals("on-save", tokens[tokens.indexOf("--refresh-style") + 1])
        assertEquals("true", tokens[tokens.indexOf("--partial-rendering") + 1])
    }

    @Test
    fun `killPreview carries the exact wire-name and the bare task id`() {
        val params = TinymistPreviewCommands.buildKillPreviewParams("tab-1")
        assertEquals("tinymist.doKillPreview", params.command)
        assertEquals(listOf<Any>("tab-1"), params.arguments)
    }

    @Test
    fun `scrollPreview sends a panelScrollTo event for the source position`() {
        val params = TinymistPreviewCommands.buildScrollPreviewParams("tab-1", Path.of("main.typ"), 12, 3)
        assertEquals("tinymist.scrollPreview", params.command)
        assertEquals(2, params.arguments.size)
        assertEquals("tab-1", params.arguments[0])

        @Suppress("UNCHECKED_CAST") val event = params.arguments[1] as Map<String, Any>
        assertEquals("panelScrollTo", event["event"])
        assertEquals(12, event["line"])
        assertEquals(3, event["character"])
        assertTrue(Path.of(event["filepath"] as String).isAbsolute)
    }

    // ---- response reading ----

    @Test
    fun `session is read from a Map response with widened numbers`() { // lsp4j hands a JSON object back as a Map whose numbers are Doubles — an
        // `as Int` cast here would throw on every successful start.
        val response = mapOf(
            "staticServerPort" to 58359.0,
            "dataPlanePort" to 58359.0,
            "staticServerAddr" to "127.0.0.1:58359",
            "isPrimary" to true,
        )
        val session = TinymistPreviewCommands.extractPreviewSession("tab-1", response)
        assertNotNull(session)
        assertEquals(58359, session!!.staticServerPort)
        assertEquals(58359, session.dataPlanePort)
        assertTrue(session.isPrimary)
        assertEquals("tab-1", session.taskId)
    }

    @Test
    fun `session is read from a Gson JsonObject response`() {
        val json = JsonParser.parseString(
            """{"dataPlanePort":64888,"isPrimary":false,
                "staticServerAddr":"127.0.0.1:64888","staticServerPort":64888}"""
        ) as JsonObject
        val session = TinymistPreviewCommands.extractPreviewSession("tab-2", json)
        assertNotNull(session)
        assertEquals(64888, session!!.staticServerPort)
        assertFalse(session.isPrimary)
    }

    @Test
    fun `the session url points the browser at the static server`() {
        val session = TinymistPreviewCommands.extractPreviewSession(
            "tab-1", mapOf("staticServerPort" to 58359.0)
        )
        assertEquals("http://127.0.0.1:58359", session!!.url)
    }

    @Test
    fun `a response with no usable port yields no session`() { // Reported as a failed start rather than a session, so the caller falls back
        // instead of loading a dead URL into the browser.
        assertNull(TinymistPreviewCommands.extractPreviewSession("t", null))
        assertNull(TinymistPreviewCommands.extractPreviewSession("t", "not an object"))
        assertNull(TinymistPreviewCommands.extractPreviewSession("t", emptyMap<String, Any>()))
        assertNull(TinymistPreviewCommands.extractPreviewSession("t", mapOf("staticServerPort" to 0.0)))
    }

    @Test
    fun `a missing data plane port falls back to the static server port`() {
        val session = TinymistPreviewCommands.extractPreviewSession(
            "t", mapOf("staticServerPort" to 1234.0)
        )
        assertEquals(1234, session!!.dataPlanePort)
    }
}

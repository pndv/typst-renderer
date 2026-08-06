package com.github.pndv.typstrenderer.lsp

import com.google.gson.JsonNull
import com.google.gson.JsonPrimitive
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path
import java.util.concurrent.CompletionException

/**
 * Pure-function tests for the request-building side of [TinymistCommands].
 *
 * The send-side ([TinymistCommands.exportPdf], [TinymistCommands.pinMain],
 * [TinymistCommands.getServerInfo]) needs a running LSP server and is exercised
 * end-to-end by manual verification — there's no value in mocking the entire
 * `LspServer` interface just to assert "the wrapper called sendRequestSync".
 */
class TinymistCommandsTest {

    @Test
    fun `exportPdf request carries the exact wire-name`() {
        val params = TinymistCommands.buildExportPdfParams(Path.of("foo.typ"))
        assertEquals("tinymist.exportPdf", params.command)
    }

    @Test
    fun `exportPdf request absolutises the source path`() {
        val params = TinymistCommands.buildExportPdfParams(Path.of("foo.typ"))
        assertEquals(1, params.arguments.size)
        val arg = params.arguments[0] as String
        assertTrue("argument must be absolute: $arg", Path.of(arg).isAbsolute)
        assertTrue("argument must preserve filename: $arg", arg.endsWith("foo.typ"))
    }

    @Test
    fun `pinMain carries the exact wire-name`() {
        val params = TinymistCommands.buildPinMainParams(Path.of("main.typ"))
        assertEquals("tinymist.pinMain", params.command)
    }

    @Test
    fun `pinMain with a path absolutises and stringifies it`() {
        val params = TinymistCommands.buildPinMainParams(Path.of("main.typ"))
        assertEquals(1, params.arguments.size)
        val arg = params.arguments[0] as String
        assertTrue("pin target must be absolute: $arg", Path.of(arg).isAbsolute)
        assertTrue("pin target must preserve filename: $arg", arg.endsWith("main.typ"))
    }

    @Test
    fun `pinMain with null sends a one-element list containing JsonNull`() {
        val params =
            TinymistCommands.buildPinMainParams(null) // Wire shape per tinymist's args[0] as Option<PathBuf> parser: `[null]` // (one element, JSON null) — not `[]` and not a null arguments field.
        // JsonNull is needed because lsp4j strips raw Kotlin nulls from
        // List<Any> before serialisation.
        assertEquals(1, params.arguments.size)
        assertSame(JsonNull.INSTANCE, params.arguments[0])
    }

    @Test
    fun `getServerInfo carries the exact wire-name and takes no arguments`() {
        val params = TinymistCommands.buildGetServerInfoParams()
        assertEquals("tinymist.getServerInfo", params.command)
        assertTrue(params.arguments.isEmpty())
    }

    @Test
    fun `extractServerErrorMessage returns the ResponseError message`() { // tinymist packs its formatted compile diagnostic into the JSON-RPC
        // error message; this is the text we want in the Typst Output console.
        val diagnostic = "error: label `<ch:cases>` occurs multiple times in the document"
        val ex = ResponseErrorException(
            ResponseError(ResponseErrorCode.InternalError, diagnostic, null)
        )
        assertEquals(diagnostic, TinymistCommands.extractServerErrorMessage(ex))
    }

    @Test
    fun `extractServerErrorMessage peels a CompletionException wrapper`() { // CompletableFuture wraps the server error in a CompletionException before
        // it reaches us — the unwrap must see through that layer.
        val ex = CompletionException(
            ResponseErrorException(
                ResponseError(ResponseErrorCode.InternalError, "boom", null)
            )
        )
        assertEquals("boom", TinymistCommands.extractServerErrorMessage(ex))
    }

    @Test
    fun `extractServerErrorMessage falls back to the throwable message`() { // A non-LSP failure (anything that isn't a ResponseErrorException) must
        // still yield a usable string rather than collapsing to a blank message.
        val ex = IllegalStateException("plain failure")
        assertEquals("plain failure", TinymistCommands.extractServerErrorMessage(ex))
    }

    @Test
    fun `extractExportedPath reads a plain String`() {
        assertEquals("/tmp/out.pdf", TinymistCommands.extractExportedPath("/tmp/out.pdf"))
    }

    @Test
    fun `extractExportedPath reads a Gson JsonPrimitive string`() { // lsp4j commonly hands executeCommand results back as JsonPrimitive, not
        // a Kotlin String — the missed cast here was the "no output file" bug.
        assertEquals("/tmp/out.pdf", TinymistCommands.extractExportedPath(JsonPrimitive("/tmp/out.pdf")))
    }

    @Test
    fun `extractExportedPath returns null for JsonNull, blank, and null`() {
        assertNull(TinymistCommands.extractExportedPath(JsonNull.INSTANCE))
        assertNull(TinymistCommands.extractExportedPath(""))
        assertNull(TinymistCommands.extractExportedPath(null))
    }

    @Test
    fun `extractExportedPath reads the path field of a Map response`() { // tinymist may answer with a JSON object; lsp4j deserialises it as a Map.
        // The written-PDF path sits under "path".
        assertEquals("/tmp/out.pdf", TinymistCommands.extractExportedPath(mapOf("path" to "/tmp/out.pdf")))
    }

    @Test
    fun `extractExportedPath reads a JsonPrimitive value inside a Map`() { // A Gson tree map carries JsonPrimitive values, not raw Strings — toString()
        // would leave the surrounding quotes baked into the path, so we must recurse.
        assertEquals(
            "/tmp/out.pdf",
            TinymistCommands.extractExportedPath(mapOf("path" to JsonPrimitive("/tmp/out.pdf"))),
        )
    }

    @Test
    fun `extractExportedPath returns null for a Map with no path key`() { // A missing key must NOT stringify to the literal "null" and be treated as a
        // valid export destination — that was the false-positive this branch guards.
        assertNull(TinymistCommands.extractExportedPath(mapOf("other" to "value")))
        assertNull(TinymistCommands.extractExportedPath(emptyMap<String, Any>()))
    }

    @Test
    fun `extractExportedPath returns null for a non-string JsonPrimitive`() { // Only a string primitive is a path. A number/boolean must NOT be
        // coerced via toString — that would invent a bogus "42.pdf" target.
        assertNull(TinymistCommands.extractExportedPath(JsonPrimitive(42)))
        assertNull(TinymistCommands.extractExportedPath(JsonPrimitive(true)))
    }

    @Test
    fun `formatExportError lifts the quoted payload and unescapes it`() { // Shape tinymist actually returns: a Rust source-location prefix, then the
        // typst diagnostic Debug-escaped inside double-quotes (literal \n and \\).
        val raw =
            """crates\tinymist\src\task\export.rs:579:17: ExportTask(0): """ + """document is not available for export: "error: label occurs twice\n  ┌─ d:\\Projects\\sample-doc\\file.typ:6:59\n""""
        val formatted = TinymistCommands.formatExportError(raw)
        val lines = formatted.lines()

        // Now multi-line, so TypstConsoleFilter can anchor a link on the ┌─ line.
        assertEquals("error: label occurs twice", lines[0])
        assertTrue(
            "anchor line present: $formatted", lines.any {
                it.trimStart().startsWith("┌─")
            }) // Backslashes un-doubled so Path.of resolves the Windows path.
        assertTrue(
            "single backslashes: $formatted", formatted.contains("""d:\Projects\sample-doc\file.typ""")
        ) // The Rust source-location wrapper is gone — its \task must NOT become a tab.
        assertTrue("wrapper stripped: $formatted", !formatted.contains("export.rs"))
        assertTrue("no stray tab from wrapper: $formatted", !formatted.contains('\t'))
    }

    @Test
    fun `formatExportError returns the message unchanged when there is no quoted payload`() {
        val raw = "some error with no quoted section"
        assertEquals(raw, TinymistCommands.formatExportError(raw))
    }

    @Test
    fun `unescapeRustDebug decodes newlines, backslashes and brace-unicode`() { // Input runtime value: a\nb\\c\u{94d}d  (every escape literal, i.e. backslash + char)
        val input = "a\\nb\\\\c\\u{94d}d"
        assertEquals("a\nb\\c्d", TinymistCommands.unescapeRustDebug(input))
    }
}

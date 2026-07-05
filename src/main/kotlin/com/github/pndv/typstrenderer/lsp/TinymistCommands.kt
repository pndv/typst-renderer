package com.github.pndv.typstrenderer.lsp

import com.github.pndv.typstrenderer.TypstBundle
import com.google.gson.JsonNull
import com.google.gson.JsonPrimitive
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import java.nio.file.Path
import java.util.concurrent.CompletionException

/**
 * Outcome of a [TinymistCommands.exportPdf] call.
 *
 * Tinymist signals a failed export as a JSON-RPC *error response*, not a null
 * result — the formatted diagnostic (the same `error: …` text the standalone
 * typst CLI printed to stderr) rides in that error's message. We surface it
 * rather than discarding it: in multi-file projects the failing diagnostic
 * often belongs to an `#include`-d chapter file the user does not have focused,
 * so the editor gutter on the focused entry file shows nothing and the console
 * would otherwise be the user's only signal — yet carry only a generic message.
 */
internal sealed interface ExportPdfResult {
    /** tinymist wrote the PDF; [pdf] is the absolute path it reported. */
    data class Exported(val pdf: Path) : ExportPdfResult

    /** tinymist refused to export; [detail] carries its formatted diagnostic text. */
    data class Failed(val detail: String) : ExportPdfResult

    /** The LSP was not attached, timed out, or the request never reached the server. */
    data object Unavailable : ExportPdfResult
}

/**
 * Thin wrappers around tinymist's `workspace/executeCommand` surface.
 *
 * Each public entry-point locates the running tinymist `LspClient` via
 * `LspClientManager`, then routes through [LspClient.sendRequestSync]. Transport
 * failures (server not attached, timeout, server detached mid-flight) collapse to
 * a sentinel per the LSP module's contract — no exceptions to handle at the call
 * site. A *server-side* export rejection is different: tinymist returns it as a
 * JSON-RPC error whose message carries the compile diagnostic, which [exportPdf]
 * unwraps into [ExportPdfResult.Failed] instead of throwing it away.
 *
 * The request-building helpers ([buildExportPdfParams], [buildPinMainParams],
 * [buildGetServerInfoParams]) are split out as `internal` pure functions so
 * they can be exercised by fixture-free unit tests without standing up a real
 * LSP server.
 *
 * Wire-shape conventions are catalogued in [docs/platform_gotchas.md] under
 * "tinymist's executeCommand dispatcher table".
 */
internal object TinymistCommands {
    private val log = logger<TinymistCommands>()

    internal fun buildExportPdfParams(source: Path): ExecuteCommandParams = ExecuteCommandParams(
        "tinymist.exportPdf",
        listOf(source.toAbsolutePath().toString()),
    )

    /**
     * Wire-shape note: the arguments list must contain exactly one element.
     * `null` is encoded as [JsonNull.INSTANCE] because lsp4j strips raw Kotlin
     * nulls from `List<Any>` before serialisation — an empty list would be
     * rejected by tinymist's `args[0] as Option<PathBuf>` parser, and `null`
     * arguments would deserialise as the field being missing entirely.
     */
    internal fun buildPinMainParams(mainPath: Path?): ExecuteCommandParams {
        val arg: Any = mainPath?.toAbsolutePath()?.toString() ?: JsonNull.INSTANCE
        return ExecuteCommandParams("tinymist.pinMain", listOf(arg))
    }

    internal fun buildGetServerInfoParams(): ExecuteCommandParams =
        ExecuteCommandParams("tinymist.getServerInfo", emptyList())

    /**
     * Triggers a one-shot PDF export.
     *
     * Output destination is controlled globally via the `tinymist.outputPath`
     * config sent in `initializationOptions`; it is NOT a per-call argument.
     *
     * Returns:
     *  - [ExportPdfResult.Exported] with the path tinymist wrote to;
     *  - [ExportPdfResult.Failed] with tinymist's formatted diagnostic when the
     *    document did not compile (or no path came back);
     *  - [ExportPdfResult.Unavailable] when the LSP is not attached or the
     *    round-trip could not complete.
     */
    @RequiresBackgroundThread
    fun exportPdf(project: Project, source: Path): ExportPdfResult {
        val server = getClient(project) ?: run {
            log.debug { "exportPdf: no tinymist LSP attached for project ${project.name}" }
            return ExportPdfResult.Unavailable
        }
        try {
            val outcome = server.sendRequestSync { server4j ->
                val exportPdfParams = buildExportPdfParams(source)
                log.debug { "exportPdf($source) params $exportPdfParams" }
                server4j.workspaceService.executeCommand(exportPdfParams).handle { response, error ->
                    log.debug { "exportPdf raw response: type=${response?.javaClass?.name}, value=$response" }
                    when { // Server-side rejection — tinymist's compile diagnostic rides in the
                        // error message (Rust Debug-escaped); formatExportError makes it readable.
                        error != null -> ExportPdfResult.Failed(formatExportError(extractServerErrorMessage(error)))

                        // Happy path: the response is the path tinymist wrote to. lsp4j hands
                        // the JSON result back as a String or a Gson JsonPrimitive depending on
                        // the deserialiser, so extractExportedPath accepts both shapes.
                        else -> {
                            val path = extractExportedPath(response)
                            log.debug { "exportPdf [extractExportedPath] response: path=$path" }
                            if (path != null) {
                                ExportPdfResult.Exported(Path.of(path))
                            } else { // No error and no path: tinymist's outputPath substitution
                                // returned None — it could not resolve a destination (the
                                // file sits outside the project root, so $root/$dir/$name has
                                // no root to expand against).
                                ExportPdfResult.Failed(TypstBundle.message("console.compile.failed.noOutput"))
                            }
                        }
                    }
                }

            } // sendRequestSync yields null only when the round-trip itself could not complete // (server detached, internal timeout) — distinct from a server-side export // rejection, which arrives above as Failed.
            val result = outcome ?: ExportPdfResult.Unavailable
            log.debug { "exportPdf($source) -> $result" }
            return result
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            log.error("Error exporting PDF for $source", e)
            throw e
        }
    }

    /**
     * Pulls the written-PDF path out of tinymist's `exportPdf` response.
     *
     * `lsp4j` deserialises the JSON-RPC result as `Object`, which reaches us as a
     * Kotlin [String] or a Gson [JsonPrimitive] depending on the configured type
     * adapter — we accept either. Anything else (`null`, [JsonNull], a JSON
     * object) yields `null`, meaning "no usable path came back".
     */
    internal fun extractExportedPath(response: Any?): String? {
        val raw = when (response) {
            is String -> response // tinymist may answer with a JSON object, which lsp4j hands back as a
            // Map; the path lives under "path". Recurse rather than toString() the
            // value: a missing key would otherwise stringify to a literal "null",
            // and a Gson JsonPrimitive value would keep its surrounding quotes.
            is Map<*, *> -> extractExportedPath(response["path"])
            is JsonPrimitive -> {
                if (response.isString) response.asString else {
                    log.debug { "extractExportedPath: JsonPrimitive is not string. Response: $response. Returning null." }
                    null
                }
            }

            else -> {
                log.debug { "extractExportedPath: unexpected response type: ${response.toString()}. Returning null." }
                null
            }
        }
        return raw?.takeIf { it.isNotBlank() }
    }

    /**
     * Turns tinymist's raw export error into a console-ready, multi-line diagnostic.
     *
     * tinymist wraps the typst diagnostic as a Rust `Debug`-formatted string nested
     * inside a location prefix, e.g.:
     * ```
     * …export.rs:579:17: ExportTask(0): document is not available for export: "error: …\n  ┌─ d:\\…\\file.typ:6:59\n…"
     * ```
     * The nested payload has its newlines, backslashes and non-ASCII characters
     * escaped (`\n`, `\\`, `\u{94d}`), so printed verbatim it collapses onto a
     * single line — and [com.github.pndv.typstrenderer.toolWindow.TypstConsoleFilter]
     * can only anchor file links when the `┌─` location opens its own line. We lift
     * out the quoted payload and reverse the Debug escaping so the diagnostic renders
     * the way the typst CLI used to print it.
     *
     * Only the extracted payload is unescaped — never the whole message: the location
     * prefix carries Windows-style paths like `…\task\export.rs` whose `\t` would be
     * mangled into a tab. If no quoted payload is present, the raw message is returned
     * unchanged rather than risk corrupting it.
     */
    internal fun formatExportError(rawMessage: String): String {
        val payload = extractQuotedPayload(rawMessage) ?: return rawMessage
        return unescapeRustDebug(payload)
    }

    /** Returns the text between the first and last double-quote, or `null` if there isn't a pair. */
    private fun extractQuotedPayload(message: String): String? {
        val first = message.indexOf('"')
        val last = message.lastIndexOf('"')
        return if (first in 0 until last) message.substring(first + 1, last) else null
    }

    /**
     * Reverses Rust `Debug` string escaping: `\n` `\r` `\t` `\\` `\"` `\'` `\0`
     * and `\u{XXXX}` (1–6 hex digits in braces). An unrecognised escape keeps the
     * character that follows the backslash. Used to decode the diagnostic payload
     * tinymist nests inside its export-error message.
     */
    internal fun unescapeRustDebug(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i == s.lastIndex) {
                sb.append(c)
                i++
                continue
            }
            when (val next = s[i + 1]) {
                'n' -> {
                    sb.append('\n'); i += 2
                }

                'r' -> {
                    sb.append('\r'); i += 2
                }

                't' -> {
                    sb.append('\t'); i += 2
                }

                '\\' -> {
                    sb.append('\\'); i += 2
                }

                '"' -> {
                    sb.append('"'); i += 2
                }

                '\'' -> {
                    sb.append('\''); i += 2
                }

                '0' -> {
                    sb.append('\u0000'); i += 2
                }

                'u' -> {
                    val open = i + 2
                    val close = if (open < s.length && s[open] == '{') s.indexOf('}', open + 1) else -1
                    val code = if (close > open) s.substring(open + 1, close).toIntOrNull(16) else null
                    if (code != null) {
                        sb.appendCodePoint(code)
                        i = close + 1
                    } else { // Malformed \u escape — keep the backslash literally and move on.
                        sb.append(c)
                        i++
                    }
                }

                else -> {
                    sb.append(next); i += 2
                }
            }
        }
        return sb.toString()
    }

    /**
     * Pulls the human-readable diagnostic out of a failed `executeCommand` future.
     *
     * lsp4j reports a server error as [ResponseErrorException]; surfacing through
     * `CompletableFuture` wraps it again in [CompletionException]. We peel both to
     * reach the `ResponseError` message, which carries tinymist's formatted
     * `error: …` text. Falls back to the throwable's own message if the shape is
     * not what we expect, so we never lose the failure entirely.
     */
    internal fun extractServerErrorMessage(error: Throwable): String {
        val cause = (error as? CompletionException)?.cause ?: error
        val responseError = (cause as? ResponseErrorException)?.responseError
        return responseError?.message?.takeIf { it.isNotBlank() } ?: cause.message ?: cause.toString()
    }

    /**
     * Pins the LSP's compile entry to [mainPath], overriding the focused-file
     * default. Pass `null` to unpin and revert to focused-file behaviour.
     *
     * The pin is server-side runtime-only — re-send on every LSP attach to
     * survive editor restarts.
     */
    @RequiresBackgroundThread
    fun pinMain(project: Project, mainPath: Path?) {
        val server = getClient(project) ?: return
        server.sendRequestSync { server4j ->
            server4j.workspaceService.executeCommand(buildPinMainParams(mainPath))
        }
    }

    /**
     * Returns tinymist's build info (version, embedded typst version) — useful
     * for diagnostic logging and as a cheap smoke-test of the executeCommand
     * pathway. `null` when the LSP isn't attached.
     */
    @RequiresBackgroundThread
    fun getServerInfo(project: Project): Any? {
        val server = getClient(project) ?: return null
        return server.sendRequestSync { server4j ->
            server4j.workspaceService.executeCommand(buildGetServerInfoParams())
        }
    }

    /** Returns `True` only when a tinymist server is attached AND finished initialising. */
    fun isServerReady(project: Project): Boolean {
        val server = getClient(project) ?: return false
        return server.state == LspServerState.Running
    }

    private fun getClient(project: Project): LspClient? {
        if (project.isDisposed) return null
        return LspClientManager.getInstance(project).getClients(TinymistLspServerSupportProvider::class.java)
            .firstOrNull()
    }
}

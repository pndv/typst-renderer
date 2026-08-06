package com.github.pndv.typstrenderer.lsp

import com.github.pndv.typstrenderer.TypstBundle
import com.google.gson.JsonNull
import com.google.gson.JsonPrimitive
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
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
        val arguments = listOf(arg)
        log.debug("pinMain arg: $arg. Will execute command: `tinymist.pinMain $arguments`")
        return ExecuteCommandParams("tinymist.pinMain", arguments)
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
        val server = getClient(project, source) ?: run {
            log.debug { "exportPdf: no tinymist LSP attached for $source in project ${project.name}" }
            return ExportPdfResult.Unavailable
        }
        refreshEntryForExternalFile(server, source)
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
        val error = unescapeRustDebug(payload)

        log.debug("rawMessage:\n$rawMessage\nformatted error:\n$error")
        return error
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
     * Makes tinymist re-read [source] from disk before exporting it — external-file client only.
     *
     * A `.typ` file outside the project content roots never receives `textDocument/didOpen` or
     * `didChange` (issue #100): the platform gates document sync on `isInContent`. tinymist
     * therefore reads such a file once, caches it, and — measurably — never re-reads it, so a
     * saved edit produces a *successful* export of the **previous** content, with no recompile
     * logged. Reproduced in isolation with a long-lived server: edit on disk → export → same page
     * count; then `pinMain` → export → the new page appears. Not filesystem-specific; a plain temp
     * directory behaves the same as a synced one.
     *
     * Pinning the file as that client's entry is also what it should have been all along — the
     * external client is rooted at the file's own folder and starts with `main: None`, so it is
     * the entry, and nothing had ever said so.
     *
     * Restricted to [TinymistExternalFileLspServerDescriptor]: in-content files are kept current
     * by the platform's own document sync, and pinning one here would fight the user's configured
     * main entry (#97).
     */
    private fun refreshEntryForExternalFile(client: LspClient, source: Path) {
        if (client.descriptor !is TinymistExternalFileLspServerDescriptor) return
        log.debug { "Re-pinning $source on the external-file client so tinymist re-reads it from disk" }
        client.sendRequestSync { server4j ->
            server4j.workspaceService.executeCommand(buildPinMainParams(source))
        }
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
        val server = getClient(project, mainPath) ?: return
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

    /**
     * Returns `True` only when a tinymist server that claims [source] is attached AND
     * finished initialising. With the project-wide and external-file clients coexisting,
     * "some server is running" is not enough — the readiness poll must wait for the one
     * that will actually take the export.
     */
    fun isServerReady(project: Project, source: Path): Boolean {
        val server = getClient(project, source) ?: return false
        return server.state == LspServerState.Running
    }

    /**
     * Locates the tinymist client responsible for [source].
     *
     * The project-wide client claims in-content files; an external-file client claims the
     * `.typ` files under its folder root — the descriptors' `isSupportedFile` predicates
     * partition the space, so at most one client claims any given file.
     *
     * A `null` [source] means a file-agnostic command (`getServerInfo`); any client will do.
     * Otherwise the contract is strict: return a client only when it *claims* [source] **and**
     * is `Running`, and `null` in every other case — never a client picked for lack of a better
     * option. Guessing is what produced the "file not found (searched at …)" reports: a request
     * for a `C:\…` file was handed to a client rooted on `D:\`, which cannot resolve it.
     * `null` maps to [ExportPdfResult.Unavailable], which callers already handle by polling
     * until the right client appears.
     */
    internal fun getClient(project: Project, source: Path? = null): LspClient? {
        if (project.isDisposed) return null
        val clients =
            LspClientManager.getInstance(project)
                .getClients(TinymistLspServerSupportProvider::class.java) // File-agnostic commands (getServerInfo) have no file to route by; any client answers.
        if (source == null) return clients.firstOrNull()

        // Plain findFileByNioFile is a non-refreshing lookup: it only sees what the VFS already
        // knows. A file created or replaced outside the IDE therefore misses, which is how a
        // deleted-and-recreated document ended up routed to a client rooted on another drive
        // (reported as "file not found (searched at …)"). Fall back to a refreshing lookup —
        // legal here because every caller is @RequiresBackgroundThread — so the file is found
        // rather than the client guessed.
        val localFs = LocalFileSystem.getInstance()
        val file = localFs.findFileByNioFile(source) ?: localFs.refreshAndFindFileByNioFile(source)
        if (file == null) {
            log.debug { "getClient: $source is not in the VFS even after refresh; no client can be chosen" }
            return null
        }

        val claimed = clients.firstOrNull { it.descriptor.isSupportedFile(file) }
        if (claimed == null) {
            log.debug { "getClient: no tinymist client claims ${file.path} (${clients.size} running)" }
            return null
        } // A client that has not finished `initialize` has no usable root or entry yet; sending it
        // a file-scoped command yields a spurious failure. Report "no client" instead so the
        // caller's readiness poll waits for this one to come up.
        if (claimed.state != LspServerState.Running) {
            log.debug { "getClient: client for ${file.path} is ${claimed.state}, not Running; treating as unavailable" }
            return null
        }
        return claimed
    }
}

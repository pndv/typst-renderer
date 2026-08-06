package com.github.pndv.typstrenderer.lsp

import com.github.pndv.typstrenderer.lsp.TinymistPreviewCommands.asInt
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import org.eclipse.lsp4j.ExecuteCommandParams
import java.nio.file.Path

/**
 * A running tinymist preview task.
 *
 * Each task owns one HTTP server on a loopback port. The same port serves both the preview
 * frontend (a single self-contained HTML page) and the WebSocket data plane the page uses for
 * incremental updates — the page derives the socket URL from `window.location`, so pointing a
 * browser at [url] is the entire client-side integration.
 */
internal data class TinymistPreviewSession(
    val taskId: String,
    val staticServerPort: Int,
    val dataPlanePort: Int,
    val isPrimary: Boolean,
) {
    /** The page to load in the preview browser. */
    val url: String get() = "http://127.0.0.1:$staticServerPort"
}

/** Outcome of a [TinymistPreviewCommands.startPreview] call. */
internal sealed interface PreviewStartResult {
    /** The preview server is up and serving at [TinymistPreviewSession.url]. */
    data class Started(val session: TinymistPreviewSession) : PreviewStartResult

    /** tinymist refused to start the preview; [detail] carries its message. */
    data class Failed(val detail: String) : PreviewStartResult

    /** The LSP was not attached, timed out, or the request never reached the server. */
    data object Unavailable : PreviewStartResult
}

/**
 * When tinymist re-renders a live preview.
 *
 * `OnType` is the point of the feature — tinymist recompiles from the in-memory document
 * as `textDocument/didChange` notifications arrive, so the preview follows the caret with
 * no save. `OnSave` exists as an escape hatch for very large documents where continuous
 * recompilation costs more than it is worth.
 */
internal enum class PreviewRefreshStyle(val wireValue: String) {
    ON_TYPE("on-type"), ON_SAVE("on-save"),
}

/**
 * How tinymist should invert the preview's colours.
 *
 * Inversion is done by the preview frontend rather than by us: it is how a light-background
 * document is made to sit comfortably in a dark IDE without recompiling the document itself.
 * Note the value is fixed when the task starts — an IDE theme switch needs the task restarted,
 * unlike the CSS tweak the PDF.js pane can apply in place.
 */
internal enum class PreviewInvertColours(val wireValue: String) {
    /** Never invert — the correct choice under a light IDE theme. */
    NEVER("never"),

    /** Let tinymist decide per element — the dark-theme choice. */
    AUTO("auto"),
}

/**
 * Wrappers around tinymist's preview command surface
 * (`tinymist.doStartPreview` / `doKillPreview` / `scrollPreview`).
 *
 * These sit alongside [TinymistCommands] rather than inside it because the lifecycle is
 * different in kind: `exportPdf` is a one-shot request/response, whereas a preview is a
 * long-lived server whose port has to be tracked and whose task has to be killed on dispose.
 *
 * Wire shapes below were verified against the pinned tinymist (v0.15.2) with a hand-driven
 * JSON-RPC probe rather than read off upstream docs — see docs/improvements.md, Tier 2.7.
 * The argument-building helpers are `internal` pure functions so the shapes can be pinned by
 * fixture-free unit tests without standing up a server.
 */
internal object TinymistPreviewCommands {
    private val log = logger<TinymistPreviewCommands>()

    /**
     * Builds the `tinymist.doStartPreview` request.
     *
     * Wire-shape note: the arguments list holds exactly one element, and that element is
     * itself a list of `tinymist preview` command-line tokens. tinymist re-parses them with
     * the same clap parser the standalone `tinymist preview` subcommand uses, so this is a
     * command line and not a JSON options object — an easy shape to get wrong.
     *
     * `--data-plane-host 127.0.0.1:0` asks the OS for a free port and binds loopback only;
     * the chosen port comes back in the response. `--no-open` suppresses tinymist's own
     * "launch the system browser" behaviour — we host the page ourselves.
     */
    internal fun buildStartPreviewParams(
        taskId: String,
        entry: Path,
        invertColours: PreviewInvertColours,
        refreshStyle: PreviewRefreshStyle,
        primary: Boolean,
        partialRendering: Boolean,
    ): ExecuteCommandParams {
        val argv = buildList {
            add("--task-id"); add(taskId)
            add("--data-plane-host"); add("$LOOPBACK:0")
            add("--invert-colors"); add(invertColours.wireValue)
            add("--refresh-style"); add(refreshStyle.wireValue)
            add("--partial-rendering"); add(partialRendering.toString())
            add("--no-open") // Exactly one task may be the compiler instance's primary. Every previewer but
            // the first has to declare itself secondary or the start request is rejected
            // with "cannot register preview to the compiler instance".
            if (!primary) add("--not-primary")
            add(entry.toAbsolutePath().toString())
        }
        log.debug { "doStartPreview argv: $argv" }
        return ExecuteCommandParams("tinymist.doStartPreview", listOf(argv))
    }

    /** Builds the `tinymist.doKillPreview` request. The task id is the sole argument. */
    internal fun buildKillPreviewParams(taskId: String): ExecuteCommandParams =
        ExecuteCommandParams("tinymist.doKillPreview", listOf(taskId))

    /**
     * Builds a `tinymist.scrollPreview` request that scrolls the preview to a source position.
     *
     * The second argument is the same event object the VS Code extension sends over the
     * preview's own channel; `panelScrollTo` is the editor→preview direction (the reverse
     * direction does not come back over LSP at all — see the class docs).
     */
    internal fun buildScrollPreviewParams(
        taskId: String,
        source: Path,
        line: Int,
        character: Int
    ): ExecuteCommandParams {
        val event = mapOf(
            "event" to "panelScrollTo",
            "filepath" to source.toAbsolutePath().toString(),
            "line" to line,
            "character" to character,
        )
        return ExecuteCommandParams("tinymist.scrollPreview", listOf(taskId, event))
    }

    /**
     * Starts a preview task for [entry] and returns the server it brought up.
     *
     * [taskId] must be unique across live tasks: starting a second task under an id that is
     * already running is rejected outright, so callers own a stable per-previewer id and
     * [killPreview] it before restarting.
     */
    @RequiresBackgroundThread
    fun startPreview(
        project: Project,
        taskId: String,
        entry: Path,
        invertColours: PreviewInvertColours,
        refreshStyle: PreviewRefreshStyle,
        primary: Boolean,
        partialRendering: Boolean,
    ): PreviewStartResult {
        val client = TinymistCommands.getClient(project, entry) ?: run {
            log.debug { "startPreview: no tinymist LSP attached for $entry in project ${project.name}" }
            return PreviewStartResult.Unavailable
        }
        return try {
            val outcome = client.sendRequestSync { server4j ->
                val params = buildStartPreviewParams(
                    taskId, entry, invertColours, refreshStyle, primary, partialRendering
                )
                server4j.workspaceService.executeCommand(params).handle { response, error ->
                    log.debug { "doStartPreview($taskId) raw response: $response, error: $error" }
                    when {
                        error != null -> PreviewStartResult.Failed(
                            TinymistCommands.extractServerErrorMessage(error)
                        )

                        else -> extractPreviewSession(
                            taskId,
                            response
                        )?.let { PreviewStartResult.Started(it) } // A response we cannot read a port out of is not a working preview:
                                // there is nothing to point the browser at. Report it as a failure
                                // so the caller falls back rather than loading a dead URL.
                                ?: PreviewStartResult.Failed("no preview port in response: $response")
                    }
                }
            }
            val result = outcome ?: PreviewStartResult.Unavailable
            log.debug { "startPreview($taskId, $entry) -> $result" }
            result
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            log.warn("Failed to start tinymist preview for $entry", e)
            PreviewStartResult.Unavailable
        }
    }

    /**
     * Stops the preview task [taskId], freeing its port.
     *
     * Killing a task that is not running answers with a "task not found" error, which is the
     * expected outcome of a double-dispose or of a kill after the server restarted — logged
     * at debug and otherwise ignored, since the caller's goal (no task under this id) holds
     * either way.
     */
    @RequiresBackgroundThread
    fun killPreview(project: Project, taskId: String, entry: Path) {
        val client = TinymistCommands.getClient(project, entry) ?: return
        try {
            client.sendRequestSync { server4j ->
                server4j.workspaceService.executeCommand(buildKillPreviewParams(taskId))
            }
            log.debug { "Killed tinymist preview task $taskId" }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            log.debug { "doKillPreview($taskId) did not complete cleanly: ${e.message}" }
        }
    }

    /**
     * Scrolls the preview to the position [line]:[character] of [source].
     *
     * Addressed by task, not by connection: every pane attached to [taskId] scrolls together.
     * That is inherent to the command — tinymist broadcasts the event to the task's viewers and
     * offers no way to single one out — and is why the caller only ever sends from the editor
     * the user is actually looking at.
     *
     * [source] is the file the caret sits in, which under a pinned main is usually *not* [entry]:
     * the task compiles the main document, and tinymist maps the position through the span of
     * whichever file it was `#include`d from.
     *
     * A failure here costs the user a scroll, not a preview, so it is swallowed at debug: the
     * position can legitimately have no rendered counterpart (a caret inside a comment, or in a
     * file the document does not include).
     */
    @RequiresBackgroundThread
    fun scrollPreview(project: Project, taskId: String, entry: Path, source: Path, line: Int, character: Int) {
        val client = TinymistCommands.getClient(project, entry) ?: return
        try {
            client.sendRequestSync { server4j ->
                server4j.workspaceService.executeCommand(
                    buildScrollPreviewParams(taskId, source, line, character)
                )
            }
            log.debug { "Scrolled preview task $taskId to $source:$line:$character" }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            log.debug { "scrollPreview($taskId) did not complete cleanly: ${e.message}" }
        }
    }

    /**
     * Reads the preview server's coordinates out of a `doStartPreview` response.
     *
     * The response is a JSON object; lsp4j hands it back either as a [Map] or as a Gson
     * [JsonObject] depending on the configured type adapter, and numbers inside a `Map` arrive
     * as [Double] rather than [Int] — hence [asInt] rather than a direct cast. Returns `null`
     * when no usable static-server port is present, which the caller treats as a failed start.
     */
    internal fun extractPreviewSession(taskId: String, response: Any?): TinymistPreviewSession? {
        val static = readNumber(response, "staticServerPort") ?: return null
        if (static <= 0) {
            log.debug { "extractPreviewSession: non-positive staticServerPort $static" }
            return null
        }
        return TinymistPreviewSession(
            taskId = taskId,
            staticServerPort = static, // The data plane shares the static server's port in the versions we support;
            // fall back to it rather than failing the start if the field is ever absent.
            dataPlanePort = readNumber(response, "dataPlanePort") ?: static,
            isPrimary = readBoolean(response, "isPrimary") ?: false,
        )
    }

    private fun readNumber(response: Any?, key: String): Int? = asInt(readField(response, key))

    private fun readBoolean(response: Any?, key: String): Boolean? = when (val v = readField(response, key)) {
        is Boolean -> v
        is JsonPrimitive -> if (v.isBoolean) v.asBoolean else null
        else -> null
    }

    private fun readField(response: Any?, key: String): Any? = when (response) {
        is Map<*, *> -> response[key]
        is JsonObject -> response.get(key)
        else -> {
            log.debug { "readField: unexpected response type ${response?.javaClass?.name}" }
            null
        }
    }

    private fun asInt(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        is JsonPrimitive -> if (value.isNumber) value.asInt else null
        is String -> value.toIntOrNull()
        else -> null
    }

    /** Preview servers bind loopback only — the page is for this IDE, not the network. */
    private const val LOOPBACK = "127.0.0.1"
}

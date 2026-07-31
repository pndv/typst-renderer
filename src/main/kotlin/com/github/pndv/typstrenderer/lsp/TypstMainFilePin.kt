package com.github.pndv.typstrenderer.lsp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspServerListener
import com.intellij.util.messages.Topic
import org.eclipse.lsp4j.InitializeResult
import java.nio.file.Path

private val log = logger<TypstMainFilePinner>()

/**
 * Broadcast whenever the configured main entry changes, so open previews re-export against
 * the new compile target.
 *
 * Required because a [com.github.pndv.typstrenderer.editor.TypstFilePreviewer] otherwise only
 * re-exports when its own file is saved or its output PDF is rewritten — a pin change is
 * neither. Without this, a pane that had already rendered keeps displaying a PDF produced
 * under the *previous* pin state: a chapter that compiles standalone caches its own short
 * render, and pinning a main afterwards never invalidates it.
 */
fun interface TypstMainFilePinChangeListener {

    fun pinChanged()

    companion object {
        val TOPIC: Topic<TypstMainFilePinChangeListener> =
            Topic.create("Typst main file pin changed", TypstMainFilePinChangeListener::class.java)
    }
}

/**
 * Drives tinymist's `tinymist.pinMain` compile-entry pin from the per-project
 * [com.github.pndv.typstrenderer.settings.TypstProjectSettingsState.typstMainFile]
 * setting.
 *
 * The pin is **runtime-only** on the tinymist side (its docs: "not persisted across
 * editor restarts"), so it has to be re-sent every time a fresh server attaches.
 * [TypstMainFilePinServerListener] does that on the project-wide client's
 * `serverInitialized`; the settings page and the Pin/Unpin actions call in here
 * directly for the live (no-restart) case.
 *
 * Every entry point hops onto a pooled thread because [TinymistCommands.pinMain] is
 * `@RequiresBackgroundThread` (it blocks on a synchronous LSP round-trip) and its
 * callers fire from the EDT or from the LSP framework's own dispatch thread. On the
 * attach path the pooled task also waits briefly for the server to reach `Running`:
 * `serverInitialized` fires as the `initialize` response is processed, a moment before
 * the platform marks the client ready to take `workspace/executeCommand` requests.
 */
internal object TypstMainFilePinner {

    /** How long the attach path waits for the server to become ready before giving up. */
    private const val READY_TIMEOUT_MS = 10_000L
    private const val READY_POLL_INTERVAL_MS = 100L

    /**
     * Replays the configured pin onto a freshly attached server (the
     * [TypstMainFilePinServerListener] path). A no-op when nothing is configured — a brand-new
     * server holds no pin, so there is nothing to clear, and open previews are already
     * rendering whatever that server produced.
     */
    fun pinIfConfigured(project: Project) = push(project, clearWhenUnset = false, notifyPreviews = false)

    /**
     * Applies a **user-driven** change to the main-entry setting: pushes the new state to the
     * LSP — pinning the configured file, or clearing the pin when the setting is blank or
     * invalid — and then tells every open preview to re-export.
     *
     * The broadcast is the load-bearing half. Pinning alone only redirects *future* exports; a
     * pane that has already rendered keeps its cached PDF, so a chapter which compiled
     * standalone before the pin goes on showing its own short render indefinitely.
     */
    fun applyConfiguredPin(project: Project) = push(project, clearWhenUnset = true, notifyPreviews = true)

    /**
     * Shared body: resolve the configured entry off the EDT, push it to tinymist, then
     * optionally broadcast.
     *
     * [clearWhenUnset] separates the two callers. A user change to a blank or invalid setting
     * must actively send `pinMain(null)` to drop a pin the running server still holds; an
     * attach replay must not, because a fresh server has none and the round-trip would be
     * wasted.
     *
     * The broadcast sits in a `finally`, outside every early return: even when no pin could be
     * sent (nothing configured, server not ready, send threw) the *setting* has still changed,
     * and previews must re-resolve their target — [resolveTypstExportTarget] then falls back to
     * the focused file, which is a different PDF from the one on screen.
     */
    private fun push(project: Project, clearWhenUnset: Boolean, notifyPreviews: Boolean) {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            try {
                val mainFile = resolveTypstMainFile(project)
                when {
                    mainFile != null -> {
                        val mainPath = Path.of(mainFile)
                        if (awaitServerReady(project, mainPath)) {
                            log.debug { "Sending tinymist.pinMain($mainPath) for project ${project.name}" }
                            TinymistCommands.pinMain(project, mainPath)
                        } else {
                            log.debug { "tinymist not ready in ${READY_TIMEOUT_MS}ms; skipping pin for ${project.name}" }
                        }
                    }

                    clearWhenUnset -> {
                        log.debug { "Clearing Typst compile-entry pin for project ${project.name}" }
                        TinymistCommands.pinMain(project, null)
                    }

                    else -> log.debug {
                        "No Typst main file configured for project ${project.name}; leaving compile entry unpinned"
                    }
                }
            } finally {
                if (notifyPreviews && !project.isDisposed) {
                    log.debug { "Broadcasting main-file pin change for project ${project.name}" }
                    project.messageBus.syncPublisher(TypstMainFilePinChangeListener.TOPIC).pinChanged()
                }
            }
        }
    }

    /**
     * Polls until the tinymist client that claims [source] reports `Running`, or the
     * timeout elapses. Returns `true` if it became ready. Runs only on a pooled thread.
     */
    private fun awaitServerReady(project: Project, source: Path): Boolean {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (project.isDisposed) return false
            if (TinymistCommands.isServerReady(project, source)) return true
            try {
                Thread.sleep(READY_POLL_INTERVAL_MS)
            } catch (e: InterruptedException) {
                log.warn("Thread interrupted while waiting for server to become ready", e)
                Thread.currentThread().interrupt()
                return false
            }
        }
        return TinymistCommands.isServerReady(project, source)
    }
}

/**
 * Re-sends the configured main-file pin whenever the project-wide tinymist server
 * finishes initialising.
 *
 * Returned from [TinymistLspServerDescriptor.lspServerListener], so it is bound to
 * exactly the project-wide client — the pinned entry is a project-scoped path an
 * external-file client (rooted elsewhere) has no business compiling against — and fires
 * on every start and restart, since each restart spins up a fresh server that initialises
 * anew. Re-pinning is idempotent, so a redundant call is harmless.
 *
 * This descriptor-owned listener is the stable public alternative to
 * `LspClientManager.addListener`, which is marked `@ApiStatus.Internal`.
 */
internal class TypstMainFilePinServerListener(private val project: Project) : LspServerListener {

    override fun serverInitialized(params: InitializeResult) {
        log.debug { "Project-wide tinymist server initialised for project ${project.name}; re-pinning main file if configured" }
        TypstMainFilePinner.pinIfConfigured(project)
    }
}

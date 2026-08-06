package com.github.pndv.typstrenderer.editor

import com.github.pndv.typstrenderer.lsp.PreviewStartResult
import com.github.pndv.typstrenderer.lsp.TinymistPreviewCommands
import com.github.pndv.typstrenderer.lsp.TinymistPreviewSession
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import java.nio.file.Path

/**
 * One previewer's claim on a live preview task.
 *
 * The task itself is owned by the project-level [TypstLivePreviewRegistry], which shares it
 * between every previewer resolving to the same compile target — so with a main file pinned all
 * the open chapters watch one render of the whole document, while unpinned files each get their
 * own. This class is only the per-pane handle: which target it currently holds, and the session
 * it was last given.
 *
 * Deliberately a collaborator rather than more methods on [TypstFilePreviewer] — that class
 * already carries the export state machine, JCEF setup, viewport bridging, theme listening and
 * VFS watching, and is slated for decomposition.
 */
internal class TypstLivePreviewSession(
    private val project: Project,
    private val previewerId: String,
) {
    private val log = logger<TypstLivePreviewSession>()

    private val registry: TypstLivePreviewRegistry get() = project.service()

    /** The task this pane is currently displaying, or `null` when it holds none. */
    @Volatile
    var session: TinymistPreviewSession? = null
        private set

    /** The target whose task this pane holds a claim on — released when it changes. */
    @Volatile
    private var heldTarget: Path? = null

    /** URL to load in the preview browser, or `null` when this pane holds no task. */
    val url: String? get() = session?.url

    /**
     * Whether this pane already holds the *currently running* task for [entry] under
     * [darkTheme] — i.e. whether re-acquiring would be pure churn.
     *
     * Load-bearing, not an optimisation. Restarting kills the task, starts a fresh one on a new
     * port and re-navigates the browser, which throws away the reader's scroll position; the
     * pin-change and theme broadcasts both fan out to *every* open previewer, so an
     * unconditional restart here scrolled every open preview back to the top.
     *
     * Checks the registry rather than a local cache: a pane sharing a task can have it restarted
     * underneath it by another pane, and a stale cached session would then wrongly report that
     * nothing needs doing.
     */
    fun isLiveFor(entry: Path, darkTheme: Boolean): Boolean {
        if (heldTarget != entry) return false
        val running = registry.current(entry) ?: return false
        return running.darkTheme == darkTheme && running.session == session
    }

    /**
     * Claims the live task for [entry], starting or joining it as needed, and releases any claim
     * this pane held on a different target first (the pin-change case, where a chapter's pane
     * switches from previewing itself to previewing the project main).
     */
    @RequiresBackgroundThread
    fun start(entry: Path, darkTheme: Boolean): PreviewStartResult {
        heldTarget?.takeIf { it != entry }?.let { previous ->
            log.debug { "[live] target changed $previous -> $entry; releasing the old claim" }
            registry.release(previous, previewerId)
            session = null
            heldTarget = null
        }

        val result = registry.acquire(entry, previewerId, darkTheme)
        if (result is PreviewStartResult.Started) {
            session = result.session
            heldTarget = entry
        }
        return result
    }

    /**
     * Scrolls the preview this pane is watching to [line]:[character] of [source].
     *
     * No-op when this pane holds no task, or when the task it held has since been killed —
     * the position is a courtesy, never worth starting a preview for.
     */
    @RequiresBackgroundThread
    fun scrollTo(source: Path, line: Int, character: Int) {
        val target = heldTarget ?: return
        val running = registry.current(target) ?: return
        TinymistPreviewCommands.scrollPreview(project, running.taskId, target, source, line, character)
    }

    /**
     * Releases this pane's claim. The task dies only if no other pane is still watching it, so
     * closing one chapter tab must not blank the preview in the others.
     *
     * Safe to call when nothing is held.
     */
    @RequiresBackgroundThread
    fun stop() {
        val target = heldTarget ?: return
        session = null
        heldTarget = null
        registry.release(target, previewerId)
    }
}

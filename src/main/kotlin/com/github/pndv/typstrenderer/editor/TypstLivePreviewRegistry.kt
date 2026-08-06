package com.github.pndv.typstrenderer.editor

import com.github.pndv.typstrenderer.lsp.*
import com.github.pndv.typstrenderer.settings.TypstSettingsState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import java.nio.file.Path
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * A live preview task and the previewers currently displaying it.
 *
 * [holders] are previewer ids, not counts, so a previewer that acquires the same target twice
 * (a redundant refresh) cannot inflate the count and leave the task alive after its pane closes.
 */
internal class SharedLivePreview(
    val taskId: String,
    val target: Path,
    val session: TinymistPreviewSession,
    val darkTheme: Boolean,
    val holders: MutableSet<String>,
)

/**
 * Hands every previewer that resolves to the same compile target the *same* tinymist preview
 * task, and kills the task when the last of them lets go.
 *
 * ## Why the key is the resolved target
 *
 * Keying on [resolveTypstExportTarget]'s answer rather than on the focused file gives the
 * intended sharing rule for free, with no separate "is a main pinned?" branch anywhere:
 *
 *  - **main pinned** — every in-project previewer resolves to that main, so they share one key
 *    and therefore one task. They are all displaying the same document; rendering it twenty
 *    times over twenty HTTP servers was pure waste.
 *  - **no pin** — each previewer resolves to its own file, so each gets its own key and its own
 *    independent preview, exactly as before.
 *  - **file outside the project** — never redirected by the resolver (it cannot be `#include`d
 *    by the project's main), so it always keys on itself and is never shared.
 *
 * This is the same insight [TypstExportCoalescer] uses to collapse concurrent exports; the
 * lifetimes differ (an export is one round-trip, a preview is a long-lived server), so the
 * mechanism is ref-counting rather than request-joining.
 *
 * ## Locking
 *
 * One monitor per target, not a single global one: starting a task is a blocking LSP round-trip,
 * and a global lock would serialise the unpinned case where twenty previewers legitimately start
 * twenty different tasks. Per-target locking still collapses the pinned case — the first arrival
 * starts, the rest block briefly and then join the task it registered.
 */
@Service(Service.Level.PROJECT)
internal class TypstLivePreviewRegistry(private val project: Project) {

    private val log = logger<TypstLivePreviewRegistry>()

    private val live = ConcurrentHashMap<Path, SharedLivePreview>()
    private val locks = ConcurrentHashMap<Path, Any>()

    /** The task currently serving [target], or `null` when none is running. */
    fun current(target: Path): SharedLivePreview? = live[target]

    /**
     * Registers [holderId] as a viewer of [target]'s preview, starting the task if it is not
     * already running — or restarting it if the running one was started under a different theme,
     * since colour inversion is fixed at start-up.
     *
     * Returns the outcome of the underlying start. A join returns [PreviewStartResult.Started]
     * carrying the already-running task's session, so callers cannot tell (and need not care)
     * whether they started it or joined it.
     */
    @RequiresBackgroundThread
    fun acquire(target: Path, holderId: String, darkTheme: Boolean): PreviewStartResult {
        synchronized(lockFor(target)) {
            val existing = live[target]
            if (existing != null && existing.darkTheme == darkTheme) {
                existing.holders += holderId
                log.debug { "[live] $holderId joined the task for $target (${existing.holders.size} viewers)" }
                return PreviewStartResult.Started(existing.session)
            }

            // Either nothing is running, or the theme flipped and the running task's baked-in
            // inversion is wrong. Carry the existing viewers across a theme restart: they are
            // still watching this target and will pick up the new URL on their own refresh.
            val holders = existing?.holders ?: mutableSetOf()
            val taskId = existing?.taskId ?: taskIdFor(target)
            if (existing != null) {
                log.debug { "[live] restarting the task for $target: theme changed" }
                TinymistPreviewCommands.killPreview(project, taskId, target)
                live.remove(target)
            }

            val settings = TypstSettingsState.getInstance()
            val result = TinymistPreviewCommands.startPreview(
                project = project,
                taskId = taskId,
                entry = target,
                invertColours = if (darkTheme) PreviewInvertColours.AUTO else PreviewInvertColours.NEVER,
                refreshStyle = if (settings.livePreviewOnType) PreviewRefreshStyle.ON_TYPE
                else PreviewRefreshStyle.ON_SAVE, // No task needs to be the compiler instance's primary — verified that a lone
                // secondary works and that secondaries coexist — so none of ours claims it.
                primary = false,
                partialRendering = false,
            )

            if (result is PreviewStartResult.Started) {
                holders += holderId
                live[target] = SharedLivePreview(taskId, target, result.session, darkTheme, holders)
                log.info("[live] task $taskId serving $target at ${result.session.url} (${holders.size} viewers)")
            } else {
                log.debug { "[live] task for $target did not start: $result" }
            }
            return result
        }
    }

    /**
     * Drops [holderId]'s claim on [target]'s preview, killing the task once nobody is watching.
     *
     * Safe to call for a target this holder never acquired, and safe to call twice — closing a
     * pane must not be able to take down a task other panes are still using.
     */
    @RequiresBackgroundThread
    fun release(target: Path, holderId: String) {
        synchronized(lockFor(target)) {
            val existing = live[target] ?: return
            if (!existing.holders.remove(holderId)) return
            if (existing.holders.isNotEmpty()) {
                log.debug { "[live] $holderId left the task for $target (${existing.holders.size} viewers remain)" }
                return
            }
            log.debug { "[live] last viewer of $target left; killing task ${existing.taskId}" }
            live.remove(target)
            locks.remove(target)
            TinymistPreviewCommands.killPreview(project, existing.taskId, target)
        }
    }

    private fun lockFor(target: Path): Any = locks.computeIfAbsent(target) { Any() }

    internal companion object {
        /**
         * A task id derived from the target rather than randomly generated, so a task orphaned
         * by a crash or a missed release is reclaimed by the next start for the same document
         * (which kills before starting) instead of accumulating servers under fresh ids.
         *
         * Prefixed so a stray task is identifiable as ours in tinymist's own logs.
         */
        internal fun taskIdFor(target: Path): String =
            "typst-renderer-" + UUID.nameUUIDFromBytes(target.toAbsolutePath().toString().toByteArray())
    }
}

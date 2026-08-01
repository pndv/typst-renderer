package com.github.pndv.typstrenderer.lsp

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val log = logger<TypstExportCoalescer>()

/**
 * Collapses concurrent `tinymist.exportPdf` requests for the **same target** into a single
 * compile, fanning the one result out to every caller.
 *
 * Necessary because a pinned main entry makes every open previewer resolve to the *same*
 * export target (see [resolveTypstExportTarget]). With a document open in 20 tabs, one pin
 * change fired 21 simultaneous exports of `main.typ`, all writing to the same
 * `output/main.pdf`: tinymist stages each export in a temp file and renames it into place, and
 * concurrent renames onto one path collide on Windows with
 * `failed to persist temporary file: Access is denied. (os error 5)`. Three of the 21 failed
 * that way; the other 18 were pure waste, recompiling an identical document.
 *
 * Keying on the **resolved target** rather than the focused file gives the right behaviour in
 * every case for free:
 *  - pin active → all in-project previewers share one key → one export;
 *  - no pin → every previewer has its own key → independent exports, exactly as before;
 *  - out-of-project files always resolve to themselves, so they never share with the main.
 *
 * Leading + trailing edge: a request arriving while an export is in flight joins that export
 * (and so may observe a PDF built a moment before its own trigger), but also arms exactly one
 * follow-up run afterwards. The follow-up rewrites the output PDF, and the previewers' existing
 * output-PDF VFS listener reloads them — so the last write always wins on screen, without a
 * stampede per keystroke.
 *
 * The debounce inside each previewer is orthogonal and still needed: it coalesces a burst of
 * saves within *one* tab, whereas this coalesces requests across *different* tabs.
 */
internal class TypstExportCoalescer(private val doExport: (Path) -> ExportPdfResult) {

    private val inFlight = ConcurrentHashMap<Path, Slot>()

    private class Slot {
        val result = CompletableFuture<ExportPdfResult>()
        val followUpRequested = AtomicBoolean(false)
    }

    /**
     * Exports [target], or joins an export already running for it.
     *
     * Blocking by design — callers are the previewer's single-thread reload executor and the
     * compile service's pooled thread, both of which already block on the LSP round-trip.
     */
    @RequiresBackgroundThread
    fun export(target: Path): ExportPdfResult {
        var iterations = 0
        while (true) {
            val existing = inFlight[target]
            if (existing != null) {
                existing.followUpRequested.set(true)
                log.debug { "Joining in-flight export of $target" }
                return awaitJoined(existing, target)
            }

            val mine = Slot()
            if (inFlight.putIfAbsent(target, mine) != null) continue // lost the race; re-read

            val result = runOwned(
                target, mine
            ) // Only loop again when someone asked while we held the slot — the trailing edge. // Bounded so a pathological request stream cannot pin this thread indefinitely.
            if (!mine.followUpRequested.get() || ++iterations >= MAX_FOLLOW_UPS) return result
            log.debug { "Follow-up export queued for $target (round $iterations)" }
        }
    }

    /** Runs the export while owning [slot], always publishing a result and freeing the slot. */
    private fun runOwned(target: Path, slot: Slot): ExportPdfResult {
        try {
            val result = doExport(target)
            inFlight.remove(target, slot)
            slot.result.complete(result)
            return result
        } catch (t: Throwable) { // Free the slot and wake joiners before propagating, otherwise every joiner blocks
            // until its timeout and the target stays permanently "in flight".
            inFlight.remove(target, slot)
            slot.result.completeExceptionally(t)
            throw t
        }
    }

    /**
     * Waits for the owning thread's result. A timeout or failure degrades to
     * [ExportPdfResult.Unavailable] rather than propagating: the caller's existing
     * retry/readiness path handles that, whereas an exception here would surface as a hard
     * preview error for a request this thread never actually issued.
     */
    private fun awaitJoined(slot: Slot, target: Path): ExportPdfResult = try {
        slot.result.get(JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    } catch (e: Exception) {
        log.debug { "Joined export of $target did not deliver a result (${e.javaClass.simpleName})" }
        ExportPdfResult.Unavailable
    }

    private companion object {
        /** Cap on trailing-edge rounds one call will serve before returning. */
        const val MAX_FOLLOW_UPS = 2

        /** Upper bound on how long a joiner waits for the owning export. */
        const val JOIN_TIMEOUT_SECONDS = 60L
    }
}

/**
 * Project-scoped owner of the export coalescer. Project-level because the collision it prevents
 * is between previewers of the same project writing to one output path.
 */
@Service(Service.Level.PROJECT)
internal class TypstExportService(private val project: Project) {

    private val coalescer = TypstExportCoalescer { target -> TinymistCommands.exportPdf(project, target) }

    @RequiresBackgroundThread
    fun exportPdf(target: Path): ExportPdfResult = coalescer.export(target)
}

package com.github.pndv.typstrenderer.compile

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger

/**
 * Project-scoped in-memory tracker for the most recently compiled / watched
 * Typst file.
 *
 * Powers the Recompile and Toggle Watch toolbar actions on the Typst Output
 * tool window. Once the user clicks into the tool window to read
 * a compiler error, the action data context no longer resolves their `.typ`
 * file (`CommonDataKeys.VIRTUAL_FILE` is unset for the title-bar context).
 * The tracker fills that gap by remembering "the file you were most recently
 * working with", so a one-click Recompile or Toggle Watch works without
 * leaving the error-reading context.
 *
 * In-memory only; not persisted across IDE restarts. After a restart the
 * tracker resets, and the toolbar actions fall back to the currently active
 * editor (see `resolveTypstTargetFromOutputContext` in the `actions`
 * package). Persisting the last-compiled path would let "Recompile" point
 * at a file that hasn't been edited (and may have been moved) since the
 * previous session — an in-memory tracker keeps the affordance honest.
 *
 * Written from arbitrary threads: `TypstCompileService.compile()` dispatches
 * its body onto a pooled thread, `TypstWatchService.startWatch()` runs on
 * the calling thread, and action `update()` callbacks read on BGT.
 * [lastCompiledFile] is `@Volatile` so a writing becomes visible to the subsequent
 * reads without locks — the only invariant we need.
 */
@Service(Service.Level.PROJECT)
class TypstLastCompiledTracker {
    private val log = logger<TypstLastCompiledTracker>()
    init { log.debug("TypstLastCompiledTracker constructed for project") }

    @Volatile
    private var lastCompiledFile: String? = null

    fun record(filePath: String) {
        lastCompiledFile = filePath
    }

    fun getLast(): String? = lastCompiledFile
}

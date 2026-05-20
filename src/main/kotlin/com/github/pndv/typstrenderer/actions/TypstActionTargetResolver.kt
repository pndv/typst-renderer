package com.github.pndv.typstrenderer.actions

import com.github.pndv.typstrenderer.compile.TypstLastCompiledTracker
import com.github.pndv.typstrenderer.language.TypstFileType
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project

/**
 * Resolves the target `.typ` file path for actions attached to the Typst
 * Output tool window's title bar.
 *
 * Strategy:
 * 1. Prefer the most recently compiled / watched file recorded by
 *    [TypstLastCompiledTracker] — this is the "do what I just did" path
 *    that powers Recompile after a user has clicked into the tool window
 *    to read an error.
 * 2. Fall back to the currently selected editor when that editor is a
 *    `.typ` file — covers the "fresh IDE, nothing compiled yet" case
 *    where the tracker is still null.
 * 3. Return `null` when neither resolves — callers should disable the
 *    action in their `update()`.
 *
 * The existing [TypstCompileAction] / [TypstWatchAction] read
 * `CommonDataKeys.VIRTUAL_FILE` from the action data context, which works
 * for the editor / project-view popup but not for the title-bar context
 * of a tool window — so a separate resolver lives here rather than
 * unifying with the existing actions.
 */
internal fun resolveTypstTargetFromOutputContext(project: Project): String? {
    val svc = project.service<TypstLastCompiledTracker>()
    val lastCompiled = svc.getLast()
    if (lastCompiled != null) {
        return lastCompiled
    }
    return activeTypstFilePath(project)
}

private fun activeTypstFilePath(project: Project): String? {
    val file = FileEditorManager.getInstance(project).selectedEditor?.file ?: return null
    return if (file.fileType == TypstFileType) file.path else null
}

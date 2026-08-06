package com.github.pndv.typstrenderer.actions

import com.github.pndv.typstrenderer.TypstBundle
import com.github.pndv.typstrenderer.editor.TypstFilePreviewer
import com.github.pndv.typstrenderer.editor.TypstPreviewMode
import com.github.pndv.typstrenderer.editor.TypstSplitEditor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAware

/**
 * Toggles a Typst preview pane between the live tinymist renderer and the PDF.js renderer.
 *
 * Serves two call sites from one class:
 *  - the split editor's left toolbar, which constructs it bound to [boundPreviewer] — several
 *    `.typ` tabs can sit in different modes, so the toolbar button must act on its own pane;
 *  - the action system (`plugin.xml`), which instantiates it with no argument so it appears in
 *    *Find Action* and can be given a keyboard shortcut. That path resolves the pane from the
 *    focused editor instead.
 *
 * Deliberately the *only* thing that changes a pane's mode: Compile and Export write a PDF and
 * report it, but never move the pane the user put in live mode — a renderer that changed itself
 * mid-session would read as a defect, and neither renderer can inherit the other's scroll state.
 */
class TypstTogglePreviewModeAction(
    private val boundPreviewer: TypstFilePreviewer? = null,
) : ToggleAction(), DumbAware {

    private val log = logger<TypstTogglePreviewModeAction>()

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    /** The toolbar's own pane when bound, otherwise whichever Typst editor currently has focus. */
    private fun resolvePreviewer(e: AnActionEvent): TypstFilePreviewer? =
        boundPreviewer ?: (e.getData(PlatformDataKeys.FILE_EDITOR) as? TypstSplitEditor)?.typstPreview

    override fun isSelected(e: AnActionEvent): Boolean =
        resolvePreviewer(e)?.currentPreviewMode() == TypstPreviewMode.LIVE

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val previewer = resolvePreviewer(e) ?: return
        val target = if (state) TypstPreviewMode.LIVE else TypstPreviewMode.PDF
        log.debug { "Preview mode toggled to ${target.id}" }
        previewer.setPreviewMode(target)
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        val previewer =
            resolvePreviewer(e) // Invisible outside a Typst split editor, so the action does not clutter Find Action
        // results for projects that have nothing to preview.
        e.presentation.isEnabledAndVisible = previewer != null
        if (previewer == null) return

        val live =
            previewer.currentPreviewMode() == TypstPreviewMode.LIVE // The label names what the pane is showing, and the description names what a click
        // would do — the toggle is otherwise ambiguous at a glance.
        e.presentation.text = TypstBundle.message(
            if (live) "action.Typst.TogglePreviewMode.live" else "action.Typst.TogglePreviewMode.pdf"
        )
        e.presentation.description = TypstBundle.message(
            if (live) "action.Typst.TogglePreviewMode.switchToPdf"
            else "action.Typst.TogglePreviewMode.switchToLive"
        )
        e.presentation.icon = if (live) AllIcons.Actions.Lightning else AllIcons.FileTypes.Text
    }
}

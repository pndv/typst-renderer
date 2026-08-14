package com.github.pndv.typstrenderer.actions

import com.github.pndv.typstrenderer.TypstBundle
import com.github.pndv.typstrenderer.editor.TypstFilePreviewer
import com.github.pndv.typstrenderer.editor.TypstPreviewMode
import com.github.pndv.typstrenderer.editor.TypstSplitEditor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.actionSystem.impl.ActionButtonWithText
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAware
import com.intellij.util.ui.JBUI
import java.awt.Insets
import javax.swing.JComponent

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
) : ToggleAction(), CustomComponentAction, DumbAware {

    private val log = logger<TypstTogglePreviewModeAction>()

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    /**
     * Renders the toggle as a button carrying its label, not a bare icon.
     *
     * A [ToggleAction] in a toolbar is drawn as an icon whose only state is a subtle pressed
     * background, which put the single control that chooses the renderer somewhere users did not
     * find it — and, once found, did not say which mode was active. Showing the text makes the
     * current mode readable at a glance, which is the whole job of this control.
     *
     * Only affects toolbar presentation; the *Find Action* and context-menu copies are unchanged.
     */
    override fun createCustomComponent(presentation: Presentation, place: String): JComponent =
        object : ActionButtonWithText(this, presentation, place, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE) {
            // Keep the label from being clipped to the icon-sized default when the text is the
            // point of the control.
            override fun getInsets(): Insets = JBUI.insets(2, 6)
        }

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

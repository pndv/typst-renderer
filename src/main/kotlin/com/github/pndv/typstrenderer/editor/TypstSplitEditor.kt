package com.github.pndv.typstrenderer.editor

import com.github.pndv.typstrenderer.TypstBundle
import com.github.pndv.typstrenderer.actions.TypstTogglePreviewModeAction
import com.github.pndv.typstrenderer.theme.TypstThemeListener
import com.github.pndv.typstrenderer.theme.TypstThemeService
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview

/**
 * A theme-aware split editor for Typst files.
 *
 * Extends [TextEditorWithPreview] and subscribes to [TypstThemeService] so that
 * the split view redraws correctly whenever the user switches between light and dark themes.
 */
class TypstSplitEditor(
    textEditor: TextEditor, // Not named `previewEditor`: the supertype already exposes an open val by that name,
    // and shadowing it would demand an override that changes its declared type.
    // Exposed so the action-system copy of the preview-mode toggle can resolve this editor's
    // own pane from the focused FileEditor.
    val typstPreview: TypstFilePreviewer,
) : TextEditorWithPreview(
    textEditor, typstPreview, TypstBundle.message("editor.split.window.name"), Layout.SHOW_EDITOR_AND_PREVIEW
) {

    private val log = logger<TypstSplitEditor>()

    /**
     * Left-hand toolbar of the split editor: the preview-renderer toggle.
     *
     * The base class leaves this group empty and builds a toolbar only when it is non-null, so
     * returning a group here is what makes the left toolbar appear at all. The action is
     * constructed against this editor's own preview pane — several `.typ` tabs can be open in
     * different modes, so a context-resolved action would toggle the wrong one.
     */
    override fun createLeftToolbarActionGroup(): ActionGroup { // Logged because the toolbar's presence is decided by the base class from this group's
        // child count: an empty group silently falls back to the floating layout toolbar and
        // the toggle never renders, which is indistinguishable from the override not firing.
        log.debug { "Building the Typst split-editor left toolbar (preview-mode toggle)" }
        return DefaultActionGroup(TypstTogglePreviewModeAction(typstPreview))
    }

    init { // Instantiate the app service so its init subscribes to LaF/editor events and republishes them on TOPIC.
        TypstThemeService.getInstance()
        ApplicationManager.getApplication().messageBus
            .connect(this)
            .subscribe(TypstThemeService.TOPIC, TypstThemeListener { _ ->
                ApplicationManager.getApplication().invokeLater {
                    component.revalidate()
                    component.repaint()
                }
            })
    }
}

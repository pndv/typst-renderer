package com.github.pndv.typstrenderer.editor

import com.github.pndv.typstrenderer.theme.TypstThemeListener
import com.github.pndv.typstrenderer.theme.TypstThemeService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview

/**
 * A theme-aware split editor for Typst files.
 *
 * Extends [TextEditorWithPreview] and subscribes to [TypstThemeService] so that
 * the split view redraws correctly whenever the user switches between light and dark themes.
 */
class TypstSplitEditor(
    textEditor: TextEditor,
    previewEditor: TypstFilePreviewer,
) : TextEditorWithPreview(
    textEditor,
    previewEditor,
    "Typst Editor",
    Layout.SHOW_EDITOR_AND_PREVIEW
) {

    init {
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

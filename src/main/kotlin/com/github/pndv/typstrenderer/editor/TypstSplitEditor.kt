package com.github.pndv.typstrenderer.editor

import com.github.pndv.typstrenderer.TypstBundle
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
    textEditor, previewEditor, TypstBundle.message("editor.split.window.name"), Layout.SHOW_EDITOR_AND_PREVIEW
) {

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

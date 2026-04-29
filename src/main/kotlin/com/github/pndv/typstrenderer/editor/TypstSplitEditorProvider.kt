package com.github.pndv.typstrenderer.editor

import com.github.pndv.typstrenderer.language.TypstFileType
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Provides a split editor for Typst files — code on the left, live PDF preview on the right.
 * Uses [TypstSplitEditor] which gives us the same toolbar toggle buttons
 * (Editor / Split / Preview) that JetBrains uses for Markdown.
 */
class TypstSplitEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean =
        file.fileType == TypstFileType

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val textEditor = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
        val previewEditor = TypstFilePreviewer(project, file)
        return TypstSplitEditor(textEditor, previewEditor)
    }

    override fun getEditorTypeId(): String = "typst-split-editor"

    /**
     * HIDE_DEFAULT ensures this split editor *replaces* the default text editor
     * for .typ files, rather than showing as an additional tab.
     */
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
//    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_OTHER_EDITORS
}

package com.github.pndv.typstrenderer.actions

import com.github.pndv.typstrenderer.TypstBundle
import com.github.pndv.typstrenderer.language.TypstIcons
import com.intellij.ide.actions.CreateFileFromTemplateAction
import com.intellij.ide.actions.CreateFileFromTemplateDialog
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory

class TypstCreateFileAction : CreateFileFromTemplateAction(
    TypstBundle.message("action.Typst.NewFile.text"),
    TypstBundle.message("action.Typst.NewFile.description"),
    TypstIcons.FILE,
), DumbAware {

    override fun buildDialog(project: Project, directory: PsiDirectory, builder: CreateFileFromTemplateDialog.Builder) {
        builder.setTitle(TypstBundle.message("action.Typst.NewFile.text"))
            .addKind("Typst file", TypstIcons.FILE, "TypstFile")
    }

    override fun getActionName(directory: PsiDirectory, newName: String, templateName: String): String =
        TypstBundle.message("action.Typst.NewFile.text")
}

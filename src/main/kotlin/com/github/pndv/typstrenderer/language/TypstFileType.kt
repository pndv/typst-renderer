package com.github.pndv.typstrenderer.language

import com.github.pndv.typstrenderer.TypstBundle
import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

object TypstFileType : LanguageFileType(TypstLanguage.INSTANCE) {

    override fun getName(): String = "Typst"

    override fun getDescription(): String = TypstBundle.message("filetype.typst.description")

    override fun getDefaultExtension(): String = "typ"

    override fun getIcon(): Icon = TypstIcons.FILE
}

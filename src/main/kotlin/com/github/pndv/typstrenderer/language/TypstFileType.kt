package com.github.pndv.typstrenderer.language

import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

class TypstFileType private constructor() : LanguageFileType(TypstLanguage.INSTANCE) {

    override fun getName(): String = "Typst"

    override fun getDescription(): String = "Typst markup file"

    override fun getDefaultExtension(): String = "typ"

    override fun getIcon(): Icon = TypstIcons.FILE

    companion object {
        @JvmField
        val INSTANCE = TypstFileType()
    }
}

package com.github.pndv.typstrenderer.language

import com.intellij.lang.Language

class TypstLanguage private constructor() : Language("Typst") {
    companion object {
        @JvmField
        val INSTANCE = TypstLanguage()
    }
}

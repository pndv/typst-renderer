package com.github.pndv.typstrenderer.language

import com.intellij.psi.tree.IElementType

/**
 * A single token type for Typst content.
 *
 * Since the plugin relies entirely on LSP for semantic analysis,
 * the lexer produces just one token type covering the whole file.
 */
class TypstTokenType(debugName: String) : IElementType(debugName, TypstLanguage.INSTANCE)

object TypstTokenTypes {
    @JvmField
    val CONTENT = TypstTokenType("CONTENT")
}

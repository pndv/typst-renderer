package com.github.pndv.typstrenderer.language

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType

/**
 * Minimal syntax highlighter for Typst files.
 *
 * Provides fine-grained token boundaries via [TypstLexer] so that the LSP
 * semantic-token overlay (from tinymist) can map highlights accurately.
 * This highlighter itself assigns **no** colours — all real colouring comes
 * from the LSP semantic tokens layer above.
 */
class TypstSyntaxHighlighter : SyntaxHighlighterBase() {

    override fun getHighlightingLexer(): Lexer = TypstLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = EMPTY_KEYS

    companion object {
        private val EMPTY_KEYS = arrayOf<TextAttributesKey>()
    }
}

class TypstSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
        TypstSyntaxHighlighter()
}

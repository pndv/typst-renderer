package com.github.pndv.typstrenderer.language

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType

/**
 * Minimal lexer for Typst files.
 *
 * Splits text into word tokens (letters, digits, underscore) and whitespace/symbol tokens,
 * all typed as [TypstTokenTypes.CONTENT]. This gives the editor fine-grained token boundaries
 * so that LSP semantic token highlights are applied correctly on top.
 *
 * All real tokenization and syntax analysis is handled by the tinymist LSP server.
 */
class TypstLexer : LexerBase() {

    private var buffer: CharSequence = ""
    private var endOffset: Int = 0
    private var tokenStart: Int = 0
    private var tokenEnd: Int = 0

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.endOffset = endOffset
        this.tokenStart = startOffset
        this.tokenEnd = findTokenEnd(startOffset)
    }

    override fun getState(): Int = 0

    override fun getTokenType(): IElementType? {
        return if (tokenStart < endOffset) TypstTokenTypes.CONTENT else null
    }

    override fun getTokenStart(): Int = tokenStart

    override fun getTokenEnd(): Int = tokenEnd

    override fun advance() {
        tokenStart = tokenEnd
        tokenEnd = findTokenEnd(tokenStart)
    }

    override fun getBufferSequence(): CharSequence = buffer

    override fun getBufferEnd(): Int = endOffset

    /**
     * Finds the end of the current token starting at [from].
     *
     * Token boundaries: a contiguous run of word characters (letters, digits, underscore)
     * forms one token; everything else (whitespace, symbols, punctuation) each character
     * is its own token. This gives the editor fine-grained boundaries for LSP overlays
     * without doing real Typst lexing.
     */
    private fun findTokenEnd(from: Int): Int {
        if (from >= endOffset) return endOffset

        val ch = buffer[from]

        // Word token: group contiguous word characters
        if (ch.isLetterOrDigit() || ch == '_') {
            var i = from + 1
            while (i < endOffset && (buffer[i].isLetterOrDigit() || buffer[i] == '_')) i++
            return i
        }

        // Non-word: single character token
        return from + 1
    }
}

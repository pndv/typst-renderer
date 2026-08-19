package com.github.pndv.typstrenderer.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [TypstLexer]'s token boundaries.
 *
 * [TypstLexer] extends `LexerBase` and depends on nothing beyond [TypstTokenTypes],
 * so it can be driven directly from a plain JUnit test without a `BasePlatformTestCase`
 * fixture — `start`/`advance` are called the same way the editor highlighter calls them.
 *
 * The lexer emits a single token type for the whole file; what these tests pin down is
 * *where the boundaries fall*, since that is the only thing the lexer decides. Runs of
 * word characters and runs of whitespace each collapse into one token, while every other
 * character stands alone.
 */
class TypstLexerTest {

    /** Drives the lexer over [text] and returns the text of each token in order. */
    private fun tokenise(text: String): List<String> {
        val lexer = TypstLexer()
        lexer.start(text, 0, text.length, 0)
        val tokens = mutableListOf<String>()
        while (lexer.tokenType != null) {
            tokens += text.substring(lexer.tokenStart, lexer.tokenEnd)
            lexer.advance()
        }
        return tokens
    }

    @Test
    fun wordCharactersCollapseIntoOneToken() {
        assertEquals(listOf("heading_2", " ", "text"), tokenise("heading_2 text"))
    }

    @Test
    fun whitespaceRunCollapsesIntoOneToken() {
        assertEquals(listOf("a", "   ", "b"), tokenise("a   b"))
    }

    @Test
    fun whitespaceRunSpanningBlankLinesAndIndentIsOneToken() {
        assertEquals(listOf("a", "\n\n    ", "b"), tokenise("a\n\n    b"))
    }

    @Test
    fun symbolsRemainSingleCharacterTokens() { // Only word and whitespace runs are grouped; adjacent punctuation stays split so
        // that a future change to symbol grouping is a deliberate one, not a silent drift.
        assertEquals(listOf("#", "let", " ", "x", " ", "=", " ", "1"), tokenise("#let x = 1"))
        assertEquals(listOf("=", "=", "="), tokenise("==="))
    }

    @Test
    fun nonBreakingSpaceJoinsTheSurroundingWhitespaceRun() { // Kotlin's Char.isWhitespace() also covers the Unicode space separators, so a
        // non-breaking space inside a gap does not split the run in two. Built from its
        // code point rather than written literally, so the character stays visible here.
        val nbsp = Character.toString(0x00A0)

        assertEquals(listOf("a", " $nbsp ", "b"), tokenise("a $nbsp b"))
    }

    @Test
    fun tokensTileTheBufferWithoutGapsOrOverlaps() {
        val text = "= Title\n\n#let x = 1  // note\n\ttext, more.\n"
        val lexer = TypstLexer()
        lexer.start(text, 0, text.length, 0)

        var nextExpectedStart = 0
        while (lexer.tokenType != null) {
            assertEquals(nextExpectedStart, lexer.tokenStart)
            nextExpectedStart = lexer.tokenEnd
            lexer.advance()
        }
        assertEquals(text.length, nextExpectedStart)
    }

    @Test
    fun startingPartWayThroughAWhitespaceRunTokenisesTheRemainder() { // The editor highlighter re-lexes incrementally from an arbitrary token boundary.
        // The lexer carries no state, so a restart inside a run must still yield the
        // remainder of that run rather than a single character.
        val text = "a    b"
        val lexer = TypstLexer()
        lexer.start(text, 3, text.length, 0)

        assertEquals(3, lexer.tokenStart)
        assertEquals(5, lexer.tokenEnd)
        assertEquals(TypstTokenTypes.CONTENT, lexer.tokenType)
    }

    @Test
    fun emptyBufferProducesNoTokens() {
        val lexer = TypstLexer()
        lexer.start("", 0, 0, 0)

        assertNull(lexer.tokenType)
        assertEquals(emptyList<String>(), tokenise(""))
    }
}

package com.github.pndv.typstrenderer.lsp

import com.intellij.lang.Language
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.platform.lsp.api.customization.LspSemanticTokensSupport
import com.intellij.psi.PsiFile
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Tests the semantic-token colour mapping in [tinymistLspCustomization].
 *
 * tinymist extends the standard LSP legend with Typst-specific token names
 * (`bool`, `heading`, `raw`, …). The platform's default mapping only knows the
 * 23 standard names and returns `null` for everything else, and a `null` key
 * makes the highlighting applier skip the token without drawing or logging
 * anything — so an unmapped type is invisible rather than obviously wrong.
 *
 * These run fixture-free: the customisation is a top-level `internal` function
 * taking its logger as a parameter, so no Project or LSP server is needed.
 */
class TinymistSemanticTokenMappingTest {

    private val customizer =
        tinymistLspCustomization(Logger.getInstance(TinymistSemanticTokenMappingTest::class.java)).semanticTokensCustomizer as LspSemanticTokensSupport

    /** A stock instance, used as the oracle for "what would the platform have done here?". */
    private val platformDefault = LspSemanticTokensSupport()

    private fun key(tokenType: String, modifiers: List<String> = emptyList()): TextAttributesKey? =
        customizer.getTextAttributesKey(tokenType, modifiers)

    @Test
    fun `bool is coloured as a keyword, matching none and auto`() { // tinymist maps `none` and `auto` to the standard `keyword` type but `true` / `false` to its own `bool`,
        // so without this branch the three read differently despite being the same kind of literal.
        assertEquals(DefaultLanguageHighlighterColors.KEYWORD, key("bool"))
    }

    @Test
    fun `each custom markup type gets a colour`() {
        assertEquals(DefaultLanguageHighlighterColors.VALID_STRING_ESCAPE, key("escape"))
        assertEquals(DefaultLanguageHighlighterColors.STRING, key("raw"))
        assertEquals(DefaultLanguageHighlighterColors.HIGHLIGHTED_REFERENCE, key("link"))
        assertEquals(DefaultLanguageHighlighterColors.METADATA, key("label"))
        assertEquals(DefaultLanguageHighlighterColors.METADATA, key("ref"))
        assertEquals(DefaultLanguageHighlighterColors.CONSTANT, key("heading"))
        assertEquals(DefaultLanguageHighlighterColors.DOC_COMMENT_MARKUP, key("marker"))
        assertEquals(DefaultLanguageHighlighterColors.DOC_COMMENT_MARKUP, key("term"))
        assertEquals(DefaultLanguageHighlighterColors.BRACES, key("delim"))
        assertEquals(DefaultLanguageHighlighterColors.BRACES, key("punct"))
    }

    @Test
    fun `deliberately unstyled custom types stay unmapped`() { // `text` is absent from tinymist's own semanticTokenScopes table, which is upstream saying ordinary markup
        // text should not be specially styled; `pol` and `error` are left to the IDE's own error highlighting.
        assertNull(key("text"))
        assertNull(key("pol"))
        assertNull(key("error"))
    }

    @Test
    fun `standard token types are left exactly as the platform would map them`() { // The regression this guards: adding a branch for a name the platform already handles (say `string`)
        // would silently override the IDE-wide default for every LSP-backed language setting we inherit.
        for (tokenType in STANDARD_TOKEN_TYPES) {
            assertEquals(
                "token type '$tokenType' should be left to the platform",
                platformDefault.getTextAttributesKey(tokenType, emptyList()),
                key(tokenType),
            )
        }
    }

    @Test
    fun `modifiers still reach the platform mapping for standard types`() { // `property` resolves differently with and without `static`; if delegation dropped the modifier list
        // the two would collapse to the same key.
        assertEquals(
            platformDefault.getTextAttributesKey("property", listOf("static")),
            key("property", listOf("static")),
        )
        assertNotNull(key("property", listOf("static")))
    }

    @Test
    fun `an unrecognised token type does not throw`() { // Guards against a future tinymist release adding a legend entry we have never seen.
        assertNull(key("some-future-tinymist-type"))
    }

    @Test
    fun `semantic tokens are requested regardless of the language id`() { // This is the on/off switch for the whole feature: if it answers `false`, no semantic tokens are ever
        // requested and every mapping above becomes unreachable. The platform default says `true` only for the
        // TEXT and textmate language ids, so a registered language like Typst would be refused — hence the
        // unconditional override, pinned here so a later "tidy-up" back to `super` fails the build instead of
        // silently draining the colour out of every `.typ` file.
        val psiFile = mock<PsiFile>()
        whenever(psiFile.language).thenReturn(Language.ANY)

        assertTrue(customizer.shouldAskServerForSemanticTokens(psiFile)) // Positive control: without the override the same file would be refused, so the override is load-bearing.
        assertFalse(platformDefault.shouldAskServerForSemanticTokens(psiFile))
    }

    private companion object {
        /** The 23 names the platform's own mapping recognises; anything else is a server extension. */
        val STANDARD_TOKEN_TYPES = listOf(
            "namespace", "type", "class", "enum", "interface", "struct", "typeParameter", "parameter",
            "variable", "property", "enumMember", "event", "function", "method", "macro", "keyword",
            "modifier", "comment", "string", "number", "regexp", "operator", "decorator",
        )
    }
}

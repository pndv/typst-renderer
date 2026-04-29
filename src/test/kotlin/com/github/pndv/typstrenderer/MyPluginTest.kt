package com.github.pndv.typstrenderer

import com.github.pndv.typstrenderer.language.TypstFile
import com.github.pndv.typstrenderer.language.TypstFileType
import com.github.pndv.typstrenderer.language.TypstLanguage
import com.intellij.lang.LanguageCommenters
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MyPluginTest : BasePlatformTestCase() {

    fun testTypstFileTypeRegistered() {
        val file = myFixture.configureByText("test.typ", "#let foo = 1")
        assertEquals(TypstFileType, file.virtualFile.fileType)
    }

    fun testTypstLanguageInstance() {
        assertNotNull(TypstLanguage.INSTANCE)
        assertEquals("Typst", TypstLanguage.INSTANCE.id)
    }

    fun testTypstFileTypeProperties() {
        assertEquals("Typst", TypstFileType.name)
        assertEquals("typ", TypstFileType.defaultExtension)
        assertNotNull(TypstFileType.icon)
    }

    fun testTypstCommenter() {
        val commenter = LanguageCommenters.INSTANCE.forLanguage(TypstLanguage.INSTANCE)
        assertNotNull(commenter)
        assertEquals("//", commenter!!.lineCommentPrefix)
        assertEquals("/*", commenter.blockCommentPrefix)
        assertEquals("*/", commenter.blockCommentSuffix)
    }

    fun testTypstPsiFileLanguage() {
        val psiFile = myFixture.configureByText("test.typ", "#let foo = 1")
        // With the ParserDefinition, the PsiFile should be a TypstFile backed by TypstLanguage
        assertInstanceOf(psiFile, TypstFile::class.java)
        assertEquals(TypstLanguage.INSTANCE, psiFile.language)
    }

    fun testTypstCommenterViaFile() {
        // Verify that commenter is found when looking up by the PsiFile's language
        // (this is the real code path used by Ctrl+/ — not the direct LanguageCommenters lookup)
        val psiFile = myFixture.configureByText("test.typ", "#let foo = 1")
        val commenter = LanguageCommenters.INSTANCE.forLanguage(psiFile.language)
        assertNotNull("Commenter should be found via PsiFile's language", commenter)
        assertEquals("//", commenter!!.lineCommentPrefix)
    }
}

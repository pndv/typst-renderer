package com.github.pndv.typstrenderer

import com.github.pndv.typstrenderer.language.TypstFile
import com.github.pndv.typstrenderer.language.TypstFileType
import com.github.pndv.typstrenderer.language.TypstLanguage
import com.github.pndv.typstrenderer.language.TypstLiveTemplateContextType
import com.intellij.codeInsight.template.impl.TemplateSettings
import com.intellij.lang.LanguageCommenters
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MyPluginTest : BasePlatformTestCase() {

    fun testTypstFileTypeRegistered() {
        if (!pluginRegisteredInTestPlatform()) return
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
        if (!pluginRegisteredInTestPlatform()) return
        val commenter = LanguageCommenters.INSTANCE.forLanguage(TypstLanguage.INSTANCE)
        assertNotNull(commenter)
        assertEquals("//", commenter!!.lineCommentPrefix)
        assertEquals("/*", commenter.blockCommentPrefix)
        assertEquals("*/", commenter.blockCommentSuffix)
    }

    fun testTypstPsiFileLanguage() {
        if (!pluginRegisteredInTestPlatform()) return
        val psiFile = myFixture.configureByText("test.typ", "#let foo = 1")
        // With the ParserDefinition, the PsiFile should be a TypstFile backed by TypstLanguage
        assertInstanceOf(psiFile, TypstFile::class.java)
        assertEquals(TypstLanguage.INSTANCE, psiFile.language)
    }

    fun testTypstCommenterViaFile() {
        if (!pluginRegisteredInTestPlatform()) return
        // Verify that commenter is found when looking up by the PsiFile's language
        // (this is the real code path used by Ctrl+/ — not the direct LanguageCommenters lookup)
        val psiFile = myFixture.configureByText("test.typ", "#let foo = 1")
        val commenter = LanguageCommenters.INSTANCE.forLanguage(psiFile.language)
        assertNotNull("Commenter should be found via PsiFile's language", commenter)
        assertEquals("//", commenter!!.lineCommentPrefix)
    }

    @Suppress("DEPRECATION")
    fun testTypstLiveTemplateContextType() {
        if (!pluginRegisteredInTestPlatform()) return
        val contextType = TypstLiveTemplateContextType()
        val typstFile = myFixture.configureByText("test.typ", "#let foo = 1")
        assertTrue("Context should match .typ files", contextType.isInContext(typstFile, 0))

        val otherFile = myFixture.configureByText("test.txt", "plain text")
        assertFalse("Context should not match non-Typst files", contextType.isInContext(otherFile, 0))
    }

    fun testTypstLiveTemplatesRegistered() {
        if (!pluginRegisteredInTestPlatform()) return
        val settings = TemplateSettings.getInstance()
        assertNotNull("figure live template should be registered", settings.getTemplate("figure", "Typst"))
        assertNotNull("heading live template should be registered", settings.getTemplate("heading", "Typst"))
    }
}

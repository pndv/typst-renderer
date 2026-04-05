package com.github.pndv.typstrenderer

import com.github.pndv.typstrenderer.language.TypstFileType
import com.github.pndv.typstrenderer.language.TypstLanguage
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
}

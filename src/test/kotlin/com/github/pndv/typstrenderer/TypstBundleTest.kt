package com.github.pndv.typstrenderer

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class TypstBundleTest : BasePlatformTestCase() {
    fun testBundleResolution() {
        val message = TypstBundle.message("action.Typst.Compile.text")
        assertEquals("Compile Typst File", message)
    }

    fun testActionTextResolution() {
        if (!pluginRegisteredInTestPlatform()) return
        val actionManager = com.intellij.openapi.actionSystem.ActionManager.getInstance()

        val compileAction = actionManager.getAction("Typst.Compile")
        assertNotNull("Action Typst.Compile should be registered", compileAction)
        assertEquals("Compile action text should be resolved", "Compile Typst File", compileAction.templatePresentation.text)
    }

    fun testNewFileBundleResolution() {
        assertEquals("Typst File", TypstBundle.message("action.Typst.NewFile.text"))
        assertEquals("Create a new Typst file", TypstBundle.message("action.Typst.NewFile.description"))
    }

    fun testNewFileActionRegistration() {
        if (!pluginRegisteredInTestPlatform()) return
        val actionManager = com.intellij.openapi.actionSystem.ActionManager.getInstance()

        val newFileAction = actionManager.getAction("Typst.NewFile")
        assertNotNull("Action Typst.NewFile should be registered", newFileAction)
        assertEquals("New file action text should be resolved", "Typst File", newFileAction.templatePresentation.text)
    }

    fun testNewFileTemplateRegistered() {
        if (!pluginRegisteredInTestPlatform()) return
        val template =
            com.intellij.ide.fileTemplates.FileTemplateManager.getInstance(project).getInternalTemplate("TypstFile")
        assertNotNull("Internal template TypstFile should be registered", template)
    }
}

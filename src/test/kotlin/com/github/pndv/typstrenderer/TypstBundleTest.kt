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
}

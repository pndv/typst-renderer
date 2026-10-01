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

    /**
     * The version-prompt strings are reached only from a startup check and a settings page, so a
     * misspelt key would first surface as `!key!` in front of a user. Resolving each one here
     * turns that into a test failure.
     */
    fun testVersionPromptMessagesResolve() {
        val keys = listOf(
            "settings.lsp.updates.notify.label",
            "settings.lsp.updates.notify.comment",
            "notification.group.typst.updates",
            "notification.tinymist.behind.action.releaseNotes",
            "notification.tinymist.behind.action.skip",
            "notification.tinymist.behind.action.disable",
        )
        for (key in keys) {
            val message = TypstBundle.message(key)
            assertFalse("Bundle key $key does not resolve", message.startsWith("!") && message.endsWith("!"))
            assertTrue("Bundle key $key resolves to nothing", message.isNotBlank())
        }
    }

    /**
     * A literal apostrophe in a parameterised message makes MessageFormat swallow the
     * substitution, leaving the user a message with `{0}` still in it. Checks the arguments
     * actually land.
     */
    fun testParameterisedVersionMessagesSubstituteTheirArguments() {
        val body = TypstBundle.message(
            "notification.tinymist.behind.body", "0.15.8", "0.15.2", "/usr/local/bin/tinymist",
        )
        assertTrue("pinned version missing from: $body", body.contains("0.15.8"))
        assertTrue("installed version missing from: $body", body.contains("0.15.2"))
        assertTrue("binary path missing from: $body", body.contains("/usr/local/bin/tinymist"))
        assertFalse("unsubstituted placeholder left in: $body", body.contains("{0}"))

        for (key in listOf("notification.tinymist.behind.title", "notification.tinymist.downloaded.version.body")) {
            val message = TypstBundle.message(key, "0.15.8")
            assertTrue("$key dropped its argument: $message", message.contains("0.15.8"))
        }
    }

    fun testNewFileTemplateRegistered() {
        if (!pluginRegisteredInTestPlatform()) return
        val template =
            com.intellij.ide.fileTemplates.FileTemplateManager.getInstance(project).getInternalTemplate("TypstFile")
        assertNotNull("Internal template TypstFile should be registered", template)
    }
}

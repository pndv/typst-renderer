package com.github.pndv.typstrenderer.lsp

import com.github.pndv.typstrenderer.settings.TypstProjectSettingsState
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Integration coverage for the *wiring* of [resolveTypstExportTarget] — specifically that
 * project membership is derived correctly from the project's content roots.
 *
 * [TypstMainFileResolverTest] already covers the decision logic by driving the pure core with a
 * `focusedFileInProject` boolean. That leaves the half which actually computes the boolean
 * untested, and it is the half that produced issue #99: the redirect originally applied to every
 * file, so pinning a main hijacked the preview of `.typ` files outside the project, exporting the
 * project's document through the project-wide client instead of the standalone file through its
 * own folder-rooted client.
 *
 * A fixture is unavoidable here: `isInProjectContent` consults `ProjectFileIndex`, which needs a
 * real project. The main file itself is created on **disk** rather than through the fixture,
 * because [resolveTypstMainFile] validates it with `java.io.File.isFile` — the light fixture's
 * in-memory `temp://` VFS is invisible to `java.io.File`.
 *
 * **Deliberately not gated on `pluginRegisteredInTestPlatform()`.** The sibling fixture tests
 * need that gate because they exercise plugin *extension points* (the `.typ` file type, the LSP
 * provider), which the 2026.2 test platform cannot resolve — it omits
 * `intellij.libraries.lucene.common`, excluding this plugin. Nothing here depends on those: the
 * assertions need only a project, `ProjectFileIndex`, and the annotation-registered
 * [TypstProjectSettingsState]. Verified running (not skipped) on the 262 test platform.
 *
 * Leaving it ungated is the safer default — an early-return gate turns a platform regression into
 * three silently-green no-ops, whereas without it the failure is loud. If these ever do start
 * failing for test-platform reasons rather than a real defect, re-add the gate as an
 * early-return `pluginRegisteredInTestPlatform()` check at the top of each test — early-return,
 * **not** `Assume`, because JUnit3 turns a failed assumption into an error.
 */
class TypstExportTargetWiringTest : BasePlatformTestCase() {

    private var diskDir: File? = null
    private lateinit var mainFileOnDisk: String

    override fun setUp() {
        super.setUp() // Real on-disk files: one to serve as the pinned main (must satisfy File.isFile), and a
        // directory that is deliberately NOT a content root of the fixture project.
        diskDir = Files.createTempDirectory("typst-export-target-wiring").toFile()
        mainFileOnDisk = File(diskDir, "main.typ").apply { writeText("= Main\n") }.absolutePath
    }

    override fun tearDown() {
        try {
            TypstProjectSettingsState.getInstance(project).typstMainFile = ""
            diskDir?.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    /** In-project chapter with a main pinned → export redirects to the main (issue #97). */
    fun testInProjectFileRedirectsToPinnedMain() {
        TypstProjectSettingsState.getInstance(project).typstMainFile = mainFileOnDisk
        val chapter = myFixture.addFileToProject("chapters/spelling-rules.typ", "@ch:cases\n").virtualFile

        assertEquals(
            "An in-content chapter must compile through the pinned main so cross-file refs resolve",
            Path.of(mainFileOnDisk),
            resolveTypstExportTarget(project, chapter),
        )
    }

    /**
     * Out-of-project file with a main pinned → compiles itself (issue #99).
     *
     * This is the regression guard. A pinned main is project-scoped and cannot `#include` a file
     * outside the content roots, so redirecting would render the wrong document — and route the
     * request to the wrong LSP client.
     */
    fun testOutOfProjectFileCompilesItself() {
        TypstProjectSettingsState.getInstance(project).typstMainFile = mainFileOnDisk

        val standalonePath = File(diskDir, "resume.typ").apply { writeText("= Resume\n") }.toPath()
        val standalone = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(standalonePath)
        assertNotNull("Precondition: the out-of-project file must be visible in the VFS", standalone)

        assertEquals(
            "A file outside the project content roots must compile itself even when a main is pinned",
            standalonePath,
            resolveTypstExportTarget(project, standalone!!),
        )
    }

    /** No main configured → every file compiles itself, in-project or not. */
    fun testNoPinConfiguredCompilesFocusedFile() {
        TypstProjectSettingsState.getInstance(project).typstMainFile = ""
        val chapter = myFixture.addFileToProject("chapters/intro.typ", "= Intro\n").virtualFile

        assertEquals(
            Path.of(chapter.path),
            resolveTypstExportTarget(project, chapter),
        )
    }
}

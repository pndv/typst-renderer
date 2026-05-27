package com.github.pndv.typstrenderer.compile

import com.github.pndv.typstrenderer.settings.TypstProjectSettingsState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Files
import org.junit.AfterClass
import org.junit.BeforeClass

/**
 * Tests for [TypstCommandBuilder]'s argv construction.
 *
 * Same fixture-free JUnit 4 pattern as `TypstRootResolverTest` and
 * `TinymistLspServerSupportProviderTest`. [TypstCommandBuilder] now returns
 * a [GeneralCommandLine] directly; [GeneralCommandLine.argv] reconstructs
 * the token list for assertions without requiring an IDE fixture.
 *
 * Argument ordering is intentionally locked in: `--root` precedes
 * `--font-path` which precedes the positional input path, with the
 * `outputPath` positional appearing last. The typst CLI itself accepts
 * mixed orders, but the explicit assertions here lock the shape so a future
 * refactor can't quietly reorder the argv and break stderr diffs / log
 * expectations.
 */
class TypstCommandBuilderTest {



    companion object {

        private val binary = "/usr/local/bin/typst"
        private val inputPath = "chapters/intro.typ"
        private val outputPath = "build/intro.pdf"
        private lateinit var root: String
        private lateinit var fontPath: String // = "/home/user/.fonts"
        private lateinit var projectSettingsAll: TypstProjectSettingsState
        private lateinit var projectSettingsRoot: TypstProjectSettingsState
        private lateinit var projectSettingsFontPath: TypstProjectSettingsState
        private val projectSettingsNone: TypstProjectSettingsState = TypstProjectSettingsState()
        val project = mock<Project>()
        private var workDir: File? = null
        private fun realDir(name: String): File = File(workDir, name).apply { mkdirs() }



        @JvmStatic
        @BeforeClass
        fun setupClass() {
            workDir = Files.createTempDirectory("typst-root-resolve-test").toFile()
            val rootDir = realDir("project-root")
            val fontPathDir = realDir("font-path")
            root = rootDir.absolutePath
            fontPath = fontPathDir.absolutePath

            projectSettingsAll = TypstProjectSettingsState().apply {
                typstFontPath = fontPath
                typstProjectRoot = root
            }

            projectSettingsRoot = TypstProjectSettingsState().apply {
                typstProjectRoot = root
            }

            projectSettingsFontPath = TypstProjectSettingsState().apply {
                typstFontPath = fontPath
            }

        }

        @JvmStatic
        @AfterClass
        fun tearDownClass() {
            workDir?.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------------
    // buildCompileCommand
    // ---------------------------------------------------------------------

    @Test
    fun compile_noOptionalArgs_returnsBinaryCompileInput() {
        whenever(project.getService(TypstProjectSettingsState::class.java)).thenReturn(projectSettingsNone)
        val commandLine = TypstCommandBuilder.buildCompileCommand(
            binary = binary,
            inputPath = inputPath,
            project = project,
        )

        assertEquals(listOf(binary, "compile", inputPath), commandLine.argv())
    }

    @Test
    fun compile_withOutputPath_appendsOutputAfterInput() {
        whenever(project.getService(TypstProjectSettingsState::class.java)).thenReturn(projectSettingsNone)
        val commandLine = TypstCommandBuilder.buildCompileCommand(
            binary = binary,
            inputPath = inputPath,
            outputPath = outputPath,
            project = project,
        )

        assertEquals(listOf(binary, "compile", inputPath, outputPath), commandLine.argv())
    }

    @Test
    fun compile_withRoot_insertsRootFlagBeforeInput() {
        whenever(project.getService(TypstProjectSettingsState::class.java)).thenReturn(projectSettingsRoot)
        val commandLine = TypstCommandBuilder.buildCompileCommand(
            binary = binary,
            inputPath = inputPath,
            project = project,
        )

        assertEquals(listOf(binary, "compile", "--root", root, inputPath), commandLine.argv())
    }

    @Test
    fun compile_withFontPath_insertsFontPathFlagBeforeInput() {
        whenever(project.getService(TypstProjectSettingsState::class.java)).thenReturn(projectSettingsFontPath)
        val commandLine = TypstCommandBuilder.buildCompileCommand(
            binary = binary,
            inputPath = inputPath,
            project = project,
        )

        assertEquals(listOf(binary, "compile", "--font-path", fontPath, inputPath), commandLine.argv())
    }

    @Test
    fun compile_withRootAndFontPath_emitsRootBeforeFontPath() {
        // Locks in the flag order: --root precedes --font-path. The typst
        // CLI accepts either order, but a stable shape keeps log diffs
        // readable when comparing failed compile runs.
        whenever(project.getService(TypstProjectSettingsState::class.java))
            .thenReturn(projectSettingsAll)
        val commandLine = TypstCommandBuilder.buildCompileCommand(
            binary = binary,
            inputPath = inputPath,
            project = project,
        )

        assertEquals(
            listOf(binary, "compile", "--root", root, "--font-path", fontPath, inputPath),
            commandLine.argv(),
        )
    }

    @Test
    fun compile_withAllFourArgs_emitsRootThenFontPathThenInputThenOutput() {
        whenever(project.getService(TypstProjectSettingsState::class.java))
            .thenReturn(projectSettingsAll)
        val commandLine = TypstCommandBuilder.buildCompileCommand(
            binary = binary,
            inputPath = inputPath,
            outputPath = outputPath,
            project = project,
        )

        assertEquals(
            listOf(
                binary, "compile",
                "--root", root,
                "--font-path", fontPath,
                inputPath,
                outputPath,
            ),
            commandLine.argv(),
        )
    }

    // ---------------------------------------------------------------------
    // buildWatchCommand
    // ---------------------------------------------------------------------

    @Test
    fun watch_noOptionalArgs_returnsBinaryWatchInput() {
        whenever(project.getService(TypstProjectSettingsState::class.java)).thenReturn(projectSettingsNone)
        val commandLine = TypstCommandBuilder.buildWatchCommand(
            binary = binary,
            inputPath = inputPath,
            project = project,
        )

        assertEquals(listOf(binary, "watch", inputPath), commandLine.argv())
    }

    @Test
    fun watch_withRoot_insertsRootFlagBeforeInput() {
        whenever(project.getService(TypstProjectSettingsState::class.java)).thenReturn(projectSettingsRoot)
        val commandLine = TypstCommandBuilder.buildWatchCommand(
            binary = binary,
            inputPath = inputPath,
            project = project,
        )

        assertEquals(listOf(binary, "watch", "--root", root, inputPath), commandLine.argv())
    }

    @Test
    fun watch_withFontPath_insertsFontPathFlagBeforeInput() {
        whenever(project.getService(TypstProjectSettingsState::class.java)).thenReturn(projectSettingsFontPath)
        val commandLine = TypstCommandBuilder.buildWatchCommand(
            binary = binary,
            inputPath = inputPath,
            project = project,
        )

        assertEquals(listOf(binary, "watch", "--font-path", fontPath, inputPath), commandLine.argv())
    }

    @Test
    fun watch_withRootAndFontPath_emitsRootBeforeFontPath() {
        whenever(project.getService(TypstProjectSettingsState::class.java))
            .thenReturn(projectSettingsAll)
        val commandLine = TypstCommandBuilder.buildWatchCommand(
            binary = binary,
            inputPath = inputPath,
            project = project,
        )

        assertEquals(
            listOf(binary, "watch", "--root", root, "--font-path", fontPath, inputPath),
            commandLine.argv(),
        )
    }

    @Test
    fun watch_withOutputPath_appendsOutputAfterInput() {
        // The previewer relies on the two-positional form (`typst watch <input> <output>`)
        // to land the PDF in a controlled location instead of next to the source.
        // Lock the trailing-positional shape so a future refactor can't drop it.
        whenever(project.getService(TypstProjectSettingsState::class.java)).thenReturn(projectSettingsNone)
        val commandLine = TypstCommandBuilder.buildWatchCommand(
            binary = binary,
            inputPath = inputPath,
            project = project,
            outputPath = outputPath,
        )

        assertEquals(listOf(binary, "watch", inputPath, outputPath), commandLine.argv())
    }

    @Test
    fun watch_withNullOutputPath_omitsTrailingPositional() {
        // Guards against an "always append" regression — passing `null`
        // explicitly must produce the same argv as omitting the parameter.
        whenever(project.getService(TypstProjectSettingsState::class.java)).thenReturn(projectSettingsNone)
        val commandLine = TypstCommandBuilder.buildWatchCommand(
            binary = binary,
            inputPath = inputPath,
            project = project,
            outputPath = null,
        )

        assertEquals(listOf(binary, "watch", inputPath), commandLine.argv())
    }

    @Test
    fun watch_withRootAndFontPathAndOutputPath_preservesFullOrdering() {
        // Full-stack assertion: --root precedes --font-path precedes inputPath
        // precedes outputPath. Belt-and-braces against a future reshuffle that
        // happens to pass the simpler tests above.
        whenever(project.getService(TypstProjectSettingsState::class.java))
            .thenReturn(projectSettingsAll)
        val commandLine = TypstCommandBuilder.buildWatchCommand(
            binary = binary,
            inputPath = inputPath,
            project = project,
            outputPath = outputPath,
        )

        assertEquals(
            listOf(
                binary, "watch",
                "--root", root,
                "--font-path", fontPath,
                inputPath,
                outputPath,
            ),
            commandLine.argv(),
        )
    }
}

/**
 * Reconstructs the full argv token list from a [GeneralCommandLine] for use
 * in test assertions. The exe path is the first element; the remaining
 * parameters follow in order.
 */
private fun GeneralCommandLine.argv(): List<String> =
    listOf(exePath) + parametersList.list

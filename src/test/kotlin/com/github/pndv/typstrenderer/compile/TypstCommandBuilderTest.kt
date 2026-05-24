package com.github.pndv.typstrenderer.compile

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [TypstCommandBuilder]'s argv construction.
 *
 * Same fixture-free JUnit 4 pattern as `TypstRootResolverTest` and
 * `TinymistLspServerSupportProviderTest`: the helper is pure, so the tests
 * never touch `GeneralCommandLine`, `Project`, or any IDE singleton.
 *
 * Argument ordering is intentionally locked in: `--root` precedes
 * `--font-path` which precedes the positional input path, with the
 * `outputPath` positional (compile-only) appearing last. The typst CLI
 * itself accepts mixed orders, but the explicit assertions here lock the
 * shape so a future refactor can't quietly reorder the argv and break
 * stderr diffs / log expectations.
 */
class TypstCommandBuilderTest {

    private val binary = "/usr/local/bin/typst"
    private val inputPath = "chapters/intro.typ"
    private val outputPath = "build/intro.pdf"
    private val root = "/home/user/project"
    private val fontPath = "/home/user/.fonts"

    // ---------------------------------------------------------------------
    // buildCompileCommand
    // ---------------------------------------------------------------------

    @Test
    fun compile_noOptionalArgs_returnsBinaryCompileInput() {
        val argv = TypstCommandBuilder.buildCompileCommand(
            binary = binary,
            inputPath = inputPath,
        )

        assertEquals(listOf(binary, "compile", inputPath), argv)
    }

    @Test
    fun compile_withOutputPath_appendsOutputAfterInput() {
        val argv = TypstCommandBuilder.buildCompileCommand(
            binary = binary,
            inputPath = inputPath,
            outputPath = outputPath,
        )

        assertEquals(listOf(binary, "compile", inputPath, outputPath), argv)
    }

    @Test
    fun compile_withRoot_insertsRootFlagBeforeInput() {
        val argv = TypstCommandBuilder.buildCompileCommand(
            binary = binary,
            inputPath = inputPath,
            root = root,
        )

        assertEquals(listOf(binary, "compile", "--root", root, inputPath), argv)
    }

    @Test
    fun compile_withFontPath_insertsFontPathFlagBeforeInput() {
        val argv = TypstCommandBuilder.buildCompileCommand(
            binary = binary,
            inputPath = inputPath,
            fontPath = fontPath,
        )

        assertEquals(listOf(binary, "compile", "--font-path", fontPath, inputPath), argv)
    }

    @Test
    fun compile_withRootAndFontPath_emitsRootBeforeFontPath() {
        // Locks in the flag order: --root precedes --font-path. The typst
        // CLI accepts either order, but a stable shape keeps log diffs
        // readable when comparing failed compile runs.
        val argv = TypstCommandBuilder.buildCompileCommand(
            binary = binary,
            inputPath = inputPath,
            root = root,
            fontPath = fontPath,
        )

        assertEquals(
            listOf(binary, "compile", "--root", root, "--font-path", fontPath, inputPath),
            argv,
        )
    }

    @Test
    fun compile_withAllFourArgs_emitsRootThenFontPathThenInputThenOutput() {
        val argv = TypstCommandBuilder.buildCompileCommand(
            binary = binary,
            inputPath = inputPath,
            outputPath = outputPath,
            root = root,
            fontPath = fontPath,
        )

        assertEquals(
            listOf(
                binary, "compile",
                "--root", root,
                "--font-path", fontPath,
                inputPath,
                outputPath,
            ),
            argv,
        )
    }

    // ---------------------------------------------------------------------
    // buildWatchCommand
    // ---------------------------------------------------------------------

    @Test
    fun watch_noOptionalArgs_returnsBinaryWatchInput() {
        val argv = TypstCommandBuilder.buildWatchCommand(
            binary = binary,
            inputPath = inputPath,
        )

        assertEquals(listOf(binary, "watch", inputPath), argv)
    }

    @Test
    fun watch_withRoot_insertsRootFlagBeforeInput() {
        val argv = TypstCommandBuilder.buildWatchCommand(
            binary = binary,
            inputPath = inputPath,
            root = root,
        )

        assertEquals(listOf(binary, "watch", "--root", root, inputPath), argv)
    }

    @Test
    fun watch_withFontPath_insertsFontPathFlagBeforeInput() {
        val argv = TypstCommandBuilder.buildWatchCommand(
            binary = binary,
            inputPath = inputPath,
            fontPath = fontPath,
        )

        assertEquals(listOf(binary, "watch", "--font-path", fontPath, inputPath), argv)
    }

    @Test
    fun watch_withRootAndFontPath_emitsRootBeforeFontPath() {
        val argv = TypstCommandBuilder.buildWatchCommand(
            binary = binary,
            inputPath = inputPath,
            root = root,
            fontPath = fontPath,
        )

        assertEquals(
            listOf(binary, "watch", "--root", root, "--font-path", fontPath, inputPath),
            argv,
        )
    }
}

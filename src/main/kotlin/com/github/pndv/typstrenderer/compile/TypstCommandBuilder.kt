package com.github.pndv.typstrenderer.compile

/**
 * Pure constructors for the `typst` CLI argv lists used by
 * [TypstCompileService] and [TypstWatchService].
 *
 * Kept as a separate object so the argument-list shape — including the
 * recently-added `--root` plumbing and the forward-looking `--font-path`
 * slot — can be tested directly without spawning a subprocess, touching
 * `GeneralCommandLine`, or wiring an IDE fixture.
 *
 * Flag ordering is intentionally fixed (`--root` before `--font-path`
 * before the positional `inputPath`, with `outputPath` last when present).
 * The typst CLI itself tolerates mixed orders, but a stable, predictable
 * argv keeps stderr diffs comparable across runs and simplifies log
 * inspection when diagnosing compile/watch issues.
 */
internal object TypstCommandBuilder {

    /**
     * Builds the argv list for a one-shot `typst compile` invocation.
     *
     * The `outputPath` positional, when present, must be last — that's the
     * only position the typst CLI accepts for the output PDF path.
     */
    fun buildCompileCommand(
        binary: String,
        inputPath: String,
        outputPath: String? = null,
        root: String? = null,
        fontPath: String? = null,
    ): List<String> = buildCommand(isWatch = false, binary, inputPath, root, fontPath, outputPath)

    /**
     * Builds the argv list for a long-running `typst watch` invocation.
     *
     * No `outputPath` analogue: in watch mode typst always writes the PDF
     * next to the source, deriving the name from the input file.
     */
    fun buildWatchCommand(
        binary: String,
        inputPath: String,
        root: String? = null,
        fontPath: String? = null,
    ): List<String> = buildCommand(isWatch = true, binary, inputPath, root, fontPath)

    private fun buildCommand(
        isWatch: Boolean,
        binary: String,
        inputPath: String,
        root: String? = null,
        fontPath: String? = null,
        outputPath: String? = null,
    ): List<String> = buildList {
        val param = if (isWatch) "watch" else "compile"
        add(binary)
        add(param)
        root?.let { add("--root"); add(it) }
        fontPath?.let { add("--font-path"); add(it) }
        add(inputPath)

        if (outputPath != null && !isWatch) {
            add(outputPath)
        }
    }
}

package com.github.pndv.typstrenderer.compile

import com.github.pndv.typstrenderer.lsp.resolveTypstFontPath
import com.github.pndv.typstrenderer.lsp.resolveTypstRoot
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * Constructors for the `typst` CLI [GeneralCommandLine] used by
 * [TypstCompileService], [TypstWatchService], and the editor previewer.
 *
 * Centralising command-line construction here means root and font-path are
 * resolved exactly once per invocation — the same values go into both the
 * argv flags (`--root`, `--font-path`) and the process working directory,
 * so the two can never drift apart.
 *
 * Flag ordering is intentionally fixed (`--root` before `--font-path`
 * before the positional `inputPath`, with `outputPath` last when present).
 * The typst CLI itself tolerates mixed orders, but a stable, predictable
 * argv keeps stderr diffs comparable across runs and simplifies log
 * inspection when diagnosing compile/watch issues.
 */
internal object TypstCommandBuilder {

    private val log = logger<TypstCommandBuilder>()

    /**
     * Builds the [GeneralCommandLine] for a one-shot `typst compile` invocation.
     *
     * The `outputPath` positional, when present, must be last — that's the
     * only position the typst CLI accepts for the output PDF path.
     */
    fun buildCompileCommand(
        binary: String,
        inputPath: String,
        project: Project,
        outputPath: String? = null,
    ): GeneralCommandLine = buildCommand(isWatch = false, binary, inputPath, project, outputPath)

    /**
     * Builds the [GeneralCommandLine] for a long-running `typst watch` invocation.
     *
     * The `outputPath` positional, when present, must be last — that's the
     * only position the typst CLI accepts for the output PDF path.
     */
    fun buildWatchCommand(
        binary: String,
        inputPath: String,
        project: Project,
        outputPath: String? = null,
    ): GeneralCommandLine = buildCommand(isWatch = true, binary, inputPath, project, outputPath)

    private fun buildCommand(
        isWatch: Boolean,
        binary: String,
        inputPath: String,
        project: Project,
        outputPath: String? = null,
    ): GeneralCommandLine {
        val param = if (isWatch) "watch" else "compile"

        // Resolve once — the same root value is used for both the --root flag
        // and withWorkingDirectory, so the two can never diverge.
        val root = resolveTypstRoot(project)
        log.debug { "Typst project root: $root" }

        val fontPath = resolveTypstFontPath(project)
        log.debug { "Typst project Font path: $fontPath" }

        val argv = buildList {
            add(binary)
            add(param)
            root?.let { add("--root"); add(it) }
            fontPath?.let { add("--font-path"); add(it) }
            add(inputPath)

            // The output PDF path must be last — it's positional in the typst and tinymist CLIs.
            if (outputPath != null) {
                log.debug { "Typst output PDF path: $outputPath (for input file $inputPath). " +
                            "Mode: ${if (isWatch) "watch" else "compile"}" }
                add(outputPath)
            }
        }

        return GeneralCommandLine(argv).apply {
            withCharset(Charsets.UTF_8)
            root?.let { withWorkingDirectory(Path.of(it)) }
        }
    }
}

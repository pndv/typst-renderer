package com.github.pndv.typstrenderer.lsp

import com.github.pndv.typstrenderer.language.TypstFileType
import com.github.pndv.typstrenderer.settings.TypstProjectSettingsState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspFormattingSupport
import com.intellij.platform.lsp.api.customization.LspSemanticTokensSupport
import com.intellij.psi.PsiFile
import java.nio.file.Path

class TinymistLspServerDescriptor(
    project: Project, private val tinymistPath: String
) : ProjectWideLspServerDescriptor(project, "Tinymist") {
    private val log = logger<TinymistLspServerDescriptor>()

    /**
     * Customizes LSP feature support for the Tinymist language server.
     *
     * Most features are enabled by default in [LspCustomization]:
     * Go to Definition, Hover, Completion, Diagnostics, Find References,
     * Code Actions, Semantic Tokens, Code Folding, Inlay Hints, Document Links.
     *
     * Only formatting is customized here to ensure tinymist always handles
     * formatting for Typst files, regardless of whether the IDE has its own formatter.
     */
    override val lspCustomization = object : LspCustomization() {

        override val formattingCustomizer = object : LspFormattingSupport() {
            override fun shouldFormatThisFileExclusivelyByServer(
                file: VirtualFile,
                ideCanFormatThisFileItself: Boolean,
                serverExplicitlyWantsToFormatThisFile: Boolean,
            ): Boolean {
                return file.fileType == TypstFileType
            }
        }

        override val semanticTokensCustomizer = object : LspSemanticTokensSupport() {
            override fun shouldAskServerForSemanticTokens(psiFile: PsiFile): Boolean = true
        }
    }

    override fun isSupportedFile(file: VirtualFile): Boolean = file.fileType == TypstFileType

    /**
     * Configures tinymist via `initializationOptions` sent in the LSP `initialize` request.
     *
     * `outputPath` template variables (`$root`, `$dir`, `$name`) are documented in
     * docs/platform_gotchas.md under "tinymist controls export destination via global config".
     * The export directory (the segment between `$root` and the mirrored `$dir/$name`) is the
     * per-project [TypstProjectSettingsState.typstExportPath] setting, defaulting to `"target"`,
     * so the resulting `$root/<exportDir>/$dir/$name` shape mirrors the source tree under that
     * directory. It relies on tinymist v0.14.18+ for the workspace-root-file fix
     * (PR #2473 — see the matching gotcha entry).
     *
     * `exportPdf` is held at `"never"` while the legacy `typst watch` subprocess still drives
     * the preview loop. Phase C of the typst-CLI retirement flips this to `"onSave"` and
     * deletes the subprocess.
     *
     * The export directory is read at LSP init, so the project settings page restarts the
     * server when it changes — same pattern as the project-root and font-path overrides.
     */
    override fun createInitializationOptions(): Any {
        val exportPath = TypstProjectSettingsState.getInstance(project).typstExportPath
        val outputPath = buildOutputPathTemplate(exportPath)
        log.debug { "tinymist initializationOptions outputPath: $outputPath" }
        return mapOf(
            "outputPath" to outputPath,
            "exportPdf" to "never",
        )
    }

    override fun createCommandLine(): GeneralCommandLine {
        log.debug { "Creating Tinymist LSP command line" }

        val typstRoot = resolveTypstRoot(project)
        log.debug { "Typst project root: $typstRoot" }

        val fontPath = resolveTypstFontPath(project)
        log.debug { "Typst project Font path: $fontPath" }

        val commandLine = GeneralCommandLine (buildList{
            add(tinymistPath)
            add("lsp")
            fontPath?.let { add("--font-path"); add(it) }
        }).apply {
            withCharset(Charsets.UTF_8)
            typstRoot?.let { withWorkingDirectory(Path.of(it)) }
        }

        log.debug {"TinyMist LSP Server CommandLine is: $commandLine"}

        return commandLine
    }
}

/**
 * Builds tinymist's `outputPath` template from the per-project export directory.
 *
 * The [exportPath] is treated as a directory relative to the workspace root; the
 * source tree is mirrored beneath it via tinymist's `$dir`/`$name` variables.
 * Surrounding whitespace and leading/trailing path separators are stripped so a
 * value like `"/out/"` does not produce `$root//out//$dir/$name`, and a blank
 * value falls back to [TypstProjectSettingsState.DEFAULT_EXPORT_PATH].
 */
internal fun buildOutputPathTemplate(exportPath: String): String {
    val exportDir = exportPath.trim().trim('/', '\\').ifEmpty { TypstProjectSettingsState.DEFAULT_EXPORT_PATH }
    return "\$root/$exportDir/\$dir/\$name"
}

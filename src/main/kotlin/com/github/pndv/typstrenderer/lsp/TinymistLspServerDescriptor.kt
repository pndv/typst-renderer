package com.github.pndv.typstrenderer.lsp

import com.github.pndv.typstrenderer.language.TypstFileType
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspFormattingSupport
import com.intellij.platform.lsp.api.customization.LspSemanticTokensSupport
import com.intellij.psi.PsiFile

class TinymistLspServerDescriptor(
    project: Project, private val tinymistPath: String
) : ProjectWideLspServerDescriptor(project, "Tinymist") {


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

    override fun createCommandLine(): GeneralCommandLine {
        return GeneralCommandLine(tinymistPath, "lsp").apply {
            withCharset(Charsets.UTF_8)
            project.basePath?.let { withWorkDirectory(it) }
        }
    }
}

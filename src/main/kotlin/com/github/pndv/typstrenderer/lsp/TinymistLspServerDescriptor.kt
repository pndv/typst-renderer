package com.github.pndv.typstrenderer.lsp

import com.github.pndv.typstrenderer.language.TypstFileType
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor

class TinymistLspServerDescriptor(
    project: Project,
    private val tinymistPath: String
) : ProjectWideLspServerDescriptor(project, "Tinymist") {

    override fun isSupportedFile(file: VirtualFile): Boolean =
        file.fileType == TypstFileType.INSTANCE

    override fun createCommandLine(): GeneralCommandLine {
        return GeneralCommandLine(tinymistPath, "lsp").apply {
            withCharset(Charsets.UTF_8)
            project.basePath?.let { withWorkDirectory(it) }
        }
    }
}

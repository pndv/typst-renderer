package com.github.pndv.typstrenderer.language

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider

/**
 * PSI file for Typst (.typ) files.
 *
 * This is a minimal stub — all real language intelligence comes from the tinymist LSP server.
 * The PSI file exists so that IntelliJ associates `.typ` files with [TypstLanguage] at the
 * PSI level, which is required for `lang.*` extension points (commenter, etc.) to work.
 */
class TypstFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, TypstLanguage.INSTANCE) {
    override fun getFileType(): FileType = TypstFileType
}

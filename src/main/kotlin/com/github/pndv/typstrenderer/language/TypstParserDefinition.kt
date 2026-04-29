package com.github.pndv.typstrenderer.language

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

/**
 * Minimal parser definition for Typst files.
 *
 * This stub parser exists so that IntelliJ creates a [TypstFile] (backed by [TypstLanguage])
 * instead of a [com.intellij.psi.impl.source.PsiPlainTextFileImpl] (backed by PlainTextLanguage)
 * when opening `.typ` files. Without this, `lang.*` extensions like `lang.commenter` are never found.
 *
 * All real parsing and semantic analysis is handled by the tinymist LSP server.
 */
class TypstParserDefinition : ParserDefinition {

    override fun createLexer(project: Project): Lexer = TypstLexer()

    override fun createParser(project: Project): PsiParser = object : PsiParser {
        override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
            val rootMarker = builder.mark()
            while (!builder.eof()) {
                builder.advanceLexer()
            }
            rootMarker.done(root)
            return builder.treeBuilt
        }
    }

    override fun getFileNodeType(): IFileElementType = FILE

    override fun getCommentTokens(): TokenSet = TokenSet.EMPTY

    override fun getStringLiteralElements(): TokenSet = TokenSet.EMPTY

    override fun createElement(node: ASTNode): PsiElement {
        return LeafPsiElement(node.elementType, node.chars)
    }

    override fun createFile(viewProvider: FileViewProvider): PsiFile = TypstFile(viewProvider)
}

val FILE = IFileElementType(TypstLanguage.INSTANCE)

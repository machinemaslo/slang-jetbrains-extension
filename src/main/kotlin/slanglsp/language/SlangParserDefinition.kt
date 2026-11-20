package slanglsp.language

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

/** Token-level PSI supplies precise source ranges; slangd handles language semantics. */
class SlangParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer {
        return SlangLexer()
    }

    override fun createParser(project: Project?): PsiParser {
        return PsiParser { root: IElementType?, builder: PsiBuilder? ->
            val file = builder!!.mark()
            while (!builder.eof()) {
                builder.advanceLexer()
            }
            file.done(root!!)
            builder.treeBuilt
        }
    }

    override fun getFileNodeType(): IFileElementType {
        return FILE
    }

    override fun getCommentTokens(): TokenSet {
        return TokenSet.create(SlangLexer.COMMENT, SlangLexer.BLOCK_COMMENT)
    }

    override fun getStringLiteralElements(): TokenSet {
        return TokenSet.create(SlangLexer.STRING)
    }

    override fun createElement(node: ASTNode): PsiElement {
        return ASTWrapperPsiElement(node)
    }

    override fun createFile(viewProvider: FileViewProvider): PsiFile {
        return object : PsiFileBase(viewProvider, SlangLanguage.INSTANCE) {
            override fun getFileType(): FileType {
                return SlangFileType
            }
        }
    }
}

private val FILE = IFileElementType(SlangLanguage.INSTANCE)

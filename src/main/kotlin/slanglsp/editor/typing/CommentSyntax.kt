package slanglsp.editor.typing

import com.intellij.lang.CodeDocumentationAwareCommenter
import com.intellij.psi.PsiComment
import com.intellij.psi.tree.IElementType
import slanglsp.language.SlangLexer

class CommentSyntax : CodeDocumentationAwareCommenter {
    override fun getLineCommentPrefix(): String {
        return "//"
    }

    override fun getBlockCommentPrefix(): String {
        return "/*"
    }

    override fun getBlockCommentSuffix(): String {
        return "*/"
    }

    override fun getCommentedBlockCommentPrefix(): String? {
        return null
    }

    override fun getCommentedBlockCommentSuffix(): String? {
        return null
    }

    override fun getLineCommentTokenType(): IElementType {
        return SlangLexer.COMMENT
    }

    override fun getBlockCommentTokenType(): IElementType {
        return SlangLexer.BLOCK_COMMENT
    }

    override fun getDocumentationCommentTokenType(): IElementType {
        return SlangLexer.BLOCK_COMMENT
    }

    override fun getDocumentationCommentPrefix(): String {
        return "/**"
    }

    override fun getDocumentationCommentLinePrefix(): String {
        return "*"
    }

    override fun getDocumentationCommentSuffix(): String {
        return "*/"
    }

    override fun isDocumentationComment(element: PsiComment): Boolean {
        return element.text.startsWith("/**")
    }
}

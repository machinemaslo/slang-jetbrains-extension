package slanglsp.formatting.native

import com.intellij.formatting.ASTBlock
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.FormattingModel
import com.intellij.formatting.FormattingModelBuilder
import com.intellij.formatting.FormattingModelProvider
import com.intellij.lang.ASTNode
import com.intellij.psi.codeStyle.CodeStyleSettings

/** Native whitespace formatting, selected through Editor / Code Style / Slang. */
class NativeFormattingModelBuilder : FormattingModelBuilder {
    override fun createModel(context: FormattingContext): FormattingModel {
        val file = context.containingFile
        val settings = context.codeStyleSettings

        return FormattingModelProvider.createFormattingModelForPsiFile(file, PsiBlock(file.node, settings), settings)
    }

    private class PsiBlock(
        private val node: ASTNode,
        settings: CodeStyleSettings,
    ) : NativeFormattingBlock(node.text, settings), ASTBlock {
        override fun getNode(): ASTNode {
            return node
        }
    }
}

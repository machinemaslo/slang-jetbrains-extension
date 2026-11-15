package slanglsp.editor;

import com.intellij.formatting.FormattingContext;
import com.intellij.formatting.FormattingModel;
import com.intellij.formatting.FormattingModelBuilder;
import com.intellij.formatting.FormattingModelProvider;
import org.jetbrains.annotations.NotNull;

/** Native whitespace formatting; clang-format remains the preferred external service. */
public final class SlangFormattingModelBuilder implements FormattingModelBuilder {
    @Override
    public @NotNull FormattingModel createModel(@NotNull FormattingContext context) {
        var file = context.getContainingFile();
        var settings = context.getCodeStyleSettings();
        return FormattingModelProvider.createFormattingModelForPsiFile(
                file, new PsiBlock(file.getNode(), settings), settings);
    }

    private static final class PsiBlock extends SlangFormattingBlock implements com.intellij.formatting.ASTBlock {
        private final com.intellij.lang.ASTNode node;

        PsiBlock(com.intellij.lang.ASTNode node, com.intellij.psi.codeStyle.CodeStyleSettings settings) {
            super(node.getText(), settings);
            this.node = node;
        }

        @Override
        public @NotNull com.intellij.lang.ASTNode getNode() {
            return node;
        }
    }
}

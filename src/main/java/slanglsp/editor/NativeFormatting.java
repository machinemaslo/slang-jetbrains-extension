package slanglsp.editor;

import com.intellij.formatting.*;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.impl.DocumentImpl;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import slanglsp.language.SlangLanguage;

/** Runs the same JetBrains formatting engine on a private document for background inspections. */
final class NativeFormatting {
    static String format(String text, CodeStyleSettings settings) {
        var model = new SnapshotModel(text, new NativeFormattingBlock(text, settings));
        // The private model has no PSI node from which the engine can infer a right margin.
        var effective = com.intellij.psi.codeStyle.CodeStyleSettingsManager.getInstance().cloneSettings(settings);
        effective.setRightMargin(null, settings.getRightMargin(SlangLanguage.INSTANCE));
        FormatterEx.getInstance().format(model, effective,
                settings.getIndentOptions(slanglsp.language.SlangFileType.INSTANCE),
                new FormatTextRanges(new TextRange(0, text.length()), true));
        return model.document.getText();
    }

    private record SnapshotModel(Document document, Block root) implements FormattingModel, FormattingDocumentModel {
        SnapshotModel(String text, Block root) {
            // This document has no PSI, editor, listeners, or undo history and is owned by this invocation.
            this(new DocumentImpl(text, true), root);
        }

        @Override
        public @NotNull Block getRootBlock() {
            return root;
        }

        @Override
        public @NotNull FormattingDocumentModel getDocumentModel() {
            return this;
        }

        @Override
        public @NotNull Document getDocument() {
            return document;
        }

        @Override
        public int getLineNumber(int offset) {
            return document.getLineNumber(offset);
        }

        @Override
        public int getLineStartOffset(int line) {
            return document.getLineStartOffset(line);
        }

        @Override
        public CharSequence getText(TextRange range) {
            return document.getText(range);
        }

        @Override
        public int getTextLength() {
            return document.getTextLength();
        }

        @Override
        public boolean containsWhiteSpaceSymbolsOnly(int start, int end) {
            return document.getText(new TextRange(start, end)).chars().allMatch(Character::isWhitespace);
        }
        @Override
        public @NotNull CharSequence adjustWhiteSpaceIfNecessary(
                @NotNull CharSequence text, int start, int end, @Nullable ASTNode node, boolean changed) {
            return text;
        }
        @Override
        public TextRange replaceWhiteSpace(TextRange range, String whitespace) {
            document.replaceString(range.getStartOffset(), range.getEndOffset(), whitespace);
            return new TextRange(range.getStartOffset(), range.getStartOffset() + whitespace.length());
        }
        @Override
        public TextRange shiftIndentInsideRange(ASTNode node, TextRange range, int indent) {
            // Multiline tokens (strings, comments, directives) are deliberately opaque.
            return range;
        }
        @Override
        public void commitChanges() {
        }
    }
}

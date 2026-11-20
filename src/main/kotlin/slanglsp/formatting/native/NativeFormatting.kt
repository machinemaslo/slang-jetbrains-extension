package slanglsp.formatting.native

import com.intellij.formatting.Block
import com.intellij.formatting.FormatTextRanges
import com.intellij.formatting.FormatterEx
import com.intellij.formatting.FormattingDocumentModel
import com.intellij.formatting.FormattingModel
import com.intellij.lang.ASTNode
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.util.TextRange
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import slanglsp.language.SlangFileType
import slanglsp.language.SlangLanguage

/** Runs the same JetBrains formatting engine on a private document for background inspections. */
internal object NativeFormatting {
    fun format(text: String, settings: CodeStyleSettings): String {
        val model = SnapshotModel(text, NativeFormattingBlock(text, settings))

        // The private model has no PSI node from which the engine can infer a right margin.
        val effective = CodeStyleSettingsManager.getInstance().cloneSettings(settings)
        effective.setRightMargin(null, settings.getRightMargin(SlangLanguage.INSTANCE))

        FormatterEx.getInstance()
            .format(
                model,
                effective,
                settings.getIndentOptions(SlangFileType),
                FormatTextRanges(TextRange(0, text.length), true),
            )

        return model.getDocument().text
    }

    private class SnapshotModel(
        text: String,
        private val root: Block,
    ) : FormattingModel, FormattingDocumentModel {
        private val snapshot = DocumentImpl(text, true)

        override fun getRootBlock(): Block {
            return root
        }

        override fun getDocumentModel(): FormattingDocumentModel {
            return this
        }

        override fun getDocument(): Document {
            return snapshot
        }

        override fun getLineNumber(offset: Int): Int {
            return snapshot.getLineNumber(offset)
        }

        override fun getLineStartOffset(line: Int): Int {
            return snapshot.getLineStartOffset(line)
        }

        override fun getText(range: TextRange): CharSequence {
            return snapshot.getText(range)
        }

        override fun getTextLength(): Int {
            return snapshot.textLength
        }

        override fun containsWhiteSpaceSymbolsOnly(start: Int, end: Int): Boolean {
            return snapshot.getText(TextRange(start, end)).all(Character::isWhitespace)
        }

        override fun adjustWhiteSpaceIfNecessary(
            text: CharSequence,
            start: Int,
            end: Int,
            node: ASTNode?,
            changed: Boolean,
        ): CharSequence {
            return text
        }

        override fun replaceWhiteSpace(range: TextRange, whitespace: String): TextRange {
            snapshot.replaceString(range.startOffset, range.endOffset, whitespace)

            return TextRange(range.startOffset, range.startOffset + whitespace.length)
        }

        override fun shiftIndentInsideRange(node: ASTNode?, range: TextRange?, indent: Int): TextRange? {
            // Multiline tokens (strings, comments, directives) are deliberately opaque.
            return range
        }

        override fun commitChanges() {
            // The snapshot has no PSI to commit.
        }
    }
}

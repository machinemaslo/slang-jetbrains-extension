package slanglsp.editor.typing

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegateAdapter
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.lineIndent.LineIndentProvider
import kotlin.math.max
import slanglsp.language.SlangLanguage
import slanglsp.language.SlangLexer

/** Expands a populated inline body before the platform inserts the requested line break. */
class SmartEnterHandler : EnterHandlerDelegateAdapter() {
    override fun postProcessEnter(file: PsiFile, editor: Editor, context: DataContext): EnterHandlerDelegate.Result {
        if (!file.language.isKindOf(SlangLanguage.INSTANCE) ||
            !CodeInsightSettings.getInstance().SMART_INDENT_ON_ENTER) {
            return EnterHandlerDelegate.Result.Continue
        }
        val document = editor.document
        val offset = editor.caretModel.offset
        val nextLine = document.getLineNumber(offset) + 1
        if (nextLine < document.lineCount) {
            val nextStart = document.getLineStartOffset(nextLine)
            val nextEnd = document.getLineEndOffset(nextLine)
            val nextText = document.immutableCharSequence.subSequence(nextStart, nextEnd).toString()
            if (nextText.trimStart().startsWith(">")) {
                val generics = GenericResolver.context(editor)
                val closing = nextStart + nextText.length - nextText.trimStart().length
                if (generics.isClosing(generics.tokenAt(closing))) {
                    EditorIndent.adjustLine(file.project, editor, nextLine)
                }
            }
        }
        val token = editor.highlighter.createIterator(max(0, offset - 1))
        if (!token.atEnd() && token.tokenType === SlangLexer.BLOCK_COMMENT) {
            val openingLine = document.getLineNumber(token.start)
            val closingLine = document.getLineNumber(token.end - 1)
            val currentLine = document.getLineNumber(offset)
            val start = document.getLineStartOffset(openingLine)
            val prefix = document.text.substring(start, token.start)
            if (prefix.isBlank() && currentLine > openingLine) {
                EnterEdits.indentCommentLine(editor, currentLine, "$prefix ")
                if (currentLine + 1 <= closingLine) {
                    EnterEdits.indentCommentLine(editor, currentLine + 1, "$prefix ")
                }
            }
        }
        return EnterHandlerDelegate.Result.Continue
    }

    override fun preprocessEnter(
        file: PsiFile,
        editor: Editor,
        caretOffset: Ref<Int?>,
        caretAdvance: Ref<Int?>,
        context: DataContext,
        originalHandler: EditorActionHandler?
    ): EnterHandlerDelegate.Result {
        if (!file.language.isKindOf(SlangLanguage.INSTANCE) ||
            !CodeInsightSettings.getInstance().SMART_INDENT_ON_ENTER) {
            return EnterHandlerDelegate.Result.Continue
        }

        val document = editor.document
        val offset: Int = caretOffset.get()!!
        if (offset == 0) {
            return EnterHandlerDelegate.Result.Continue
        }

        val text = document.immutableCharSequence
        if (offset < text.length && text[offset - 1] == '<' && text[offset] == '>') {
            val generics = GenericResolver.context(editor)
            if (generics.isOpening(generics.tokenAt(offset - 1))) {
                document.insertString(offset, "\n")
                editor.caretModel.moveToOffset(offset)
                return EnterHandlerDelegate.Result.Continue
            }
        }
        val token = editor.highlighter.createIterator(offset - 1)
        if (token.tokenType === SlangLexer.COMMENT &&
            text.subSequence(token.start, token.end).toString().startsWith("///")) {
            val start = document.getLineStartOffset(document.getLineNumber(offset))
            val indent = text.subSequence(start, token.start).toString()
            if (indent.isBlank()) {
                val continuation = "\n$indent/// "
                document.insertString(offset, continuation)
                editor.caretModel.moveToOffset(offset + continuation.length)
                return EnterHandlerDelegate.Result.Stop
            }
        }
        if (token.tokenType === SlangLexer.STRING ||
            token.tokenType === SlangLexer.COMMENT ||
            token.tokenType === SlangLexer.BLOCK_COMMENT) {
            return EnterHandlerDelegate.Result.Continue
        }

        val lineStart = document.getLineStartOffset(document.getLineNumber(offset))
        var depth = 0
        while (!token.atEnd() && token.start >= lineStart) {
            if (token.tokenType === SlangLexer.RBRACE) {
                depth++
            } else if (token.tokenType === SlangLexer.LBRACE) {
                if (depth-- == 0) {
                    val bodyStart = token.end
                    var content = bodyStart
                    while (content < offset && Character.isWhitespace(text[content])) {
                        content++
                    }

                    val atBodyEnd: Boolean =
                        EnterEdits.splitTrailingBrace(editor, token.start, offset, lineStart, content < offset)
                    if (content == offset) {
                        document.deleteString(bodyStart, content)
                        caretOffset.set(bodyStart)
                        editor.caretModel.moveToOffset(bodyStart)
                        return EnterHandlerDelegate.Result.Continue
                    }

                    document.replaceString(bodyStart, content, "\n")
                    val bodyOffset = bodyStart + 1
                    var indent =
                        EditorIndentProvider().getLineIndent(file.project, editor, SlangLanguage.INSTANCE, bodyOffset)
                    // DO_NOT_ADJUST is an identity sentinel; an ordinary empty string means zero indentation.
                    if (indent == null || indent === LineIndentProvider.DO_NOT_ADJUST) {
                        indent = ""
                    }
                    document.insertString(bodyOffset, indent)
                    val newOffset = offset + 1 + indent.length - (content - bodyStart)
                    caretOffset.set(newOffset)
                    editor.caretModel.moveToOffset(newOffset)
                    return if (atBodyEnd) EnterHandlerDelegate.Result.Stop else EnterHandlerDelegate.Result.Continue
                }
            }
            token.retreat()
        }
        return EnterHandlerDelegate.Result.Continue
    }

    private object EnterEdits {
        fun indentCommentLine(editor: Editor, line: Int, indent: String) {
            val document = editor.document
            val start = document.getLineStartOffset(line)
            val end = document.getLineEndOffset(line)
            val text = document.immutableCharSequence
            var content = start
            while (content < end && (text[content] == ' ' || text[content] == '\t')) {
                content++
            }
            if (content < end && text[content] == '*') {
                document.replaceString(start, content, indent)
            }
        }

        fun splitTrailingBrace(editor: Editor, opening: Int, caret: Int, lineStart: Int, populated: Boolean): Boolean {
            val document = editor.document
            val text = document.immutableCharSequence
            val token = editor.highlighter.createIterator(opening)
            var depth = 0
            while (!token.atEnd() && document.getLineNumber(token.start) == document.getLineNumber(opening)) {
                if (token.tokenType === SlangLexer.LBRACE) {
                    depth++
                } else if (token.tokenType === SlangLexer.RBRACE && --depth == 0) {
                    val close = token.start
                    var end = close
                    while (end > caret && Character.isWhitespace(text[end - 1])) {
                        end--
                    }
                    if (end > caret || (populated && end == caret)) {
                        var indentEnd = lineStart
                        while (indentEnd < opening && Character.isWhitespace(text[indentEnd])) {
                            indentEnd++
                        }
                        document.replaceString(end, close, "\n" + text.subSequence(lineStart, indentEnd))
                    }
                    return populated && end == caret
                }
                token.advance()
            }
            return false
        }
    }
}

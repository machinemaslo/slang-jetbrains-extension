package slanglsp.editor.typing

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import slanglsp.language.SlangLanguage
import slanglsp.language.SlangLexer

class SmartTypingHandler : TypedHandlerDelegate() {
    override fun beforeSelectionRemoved(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (c == '<' && file.language.isKindOf(SlangLanguage.INSTANCE) && AngleBrackets.surroundSelection(editor)) {
            return Result.STOP
        }
        return Result.CONTINUE
    }

    override fun beforeCharTyped(c: Char, project: Project, editor: Editor, file: PsiFile, fileType: FileType): Result {
        if (file.language.isKindOf(SlangLanguage.INSTANCE) && AngleBrackets.beforeTyped(c, editor)) {
            return Result.STOP
        }
        if (file.language.isKindOf(SlangLanguage.INSTANCE) && c == '}') {
            val offset = editor.caretModel.offset
            if (offset < editor.document.textLength &&
                editor.highlighter.createIterator(offset).tokenType === SlangLexer.RBRACE) {
                BlockIndent.reindent(project, editor, offset)
            }
        }
        return Result.CONTINUE
    }

    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (!file.language.isKindOf(SlangLanguage.INSTANCE)) {
            return Result.CONTINUE
        }
        val offset = editor.caretModel.offset
        val document = editor.document
        if (c == '<') {
            AngleBrackets.afterTyped(editor)
        } else if (c == '}' || c == ')' || c == ']') {
            val token = editor.highlighter.createIterator(offset - 1)
            if (token.tokenType === SlangLexer.RBRACE) {
                BlockIndent.reindent(project, editor, offset - 1)
            } else if (token.tokenType === SlangLexer.RPAREN || token.tokenType === SlangLexer.RBRACKET) {
                val line = document.getLineNumber(offset - 1)
                if (document.immutableCharSequence
                    .subSequence(document.getLineStartOffset(line), offset - 1)
                    .toString()
                    .isBlank()) {
                    EditorIndent.adjustLine(project, editor, line)
                }
            }
        } else if (c == ':') {
            val token = editor.highlighter.createIterator(offset - 1)
            val line = document.getLineNumber(offset - 1)
            val prefix =
                document.immutableCharSequence
                    .subSequence(document.getLineStartOffset(line), offset)
                    .toString()
                    .trimStart()
            if (token.tokenType === SlangLexer.OPERATOR &&
                (prefix.startsWith("case ") || prefix.startsWith("default:"))) {
                EditorIndent.adjustLine(project, editor, line)
            }
        } else if (c == '*' && offset >= 2 && CodeInsightSettings.getInstance().CLOSE_COMMENT_ON_ENTER) {
            val token = editor.highlighter.createIterator(offset - 1)
            if (token.tokenType === SlangLexer.BLOCK_COMMENT &&
                token.start == offset - 2 &&
                !document.text.substring(token.start, token.end).endsWith("*/")) {
                document.insertString(offset, "*/")
            }
        }
        return Result.CONTINUE
    }

    private object BlockIndent {
        fun reindent(project: Project, editor: Editor, close: Int) {
            val document = editor.document
            val endLine = document.getLineNumber(close)
            var startLine = endLine
            if (CodeInsightSettings.getInstance().REFORMAT_BLOCK_ON_RBRACE) {
                val token = editor.highlighter.createIterator(close)
                var depth = 0
                while (!token.atEnd()) {
                    if (token.tokenType === SlangLexer.RBRACE) {
                        depth++
                    } else if (token.tokenType === SlangLexer.LBRACE && --depth == 0) {
                        startLine = document.getLineNumber(token.start) + 1
                        break
                    }
                    token.retreat()
                }
            }
            for (line in startLine..endLine) {
                EditorIndent.adjustLine(project, editor, line)
            }
        }
    }
}

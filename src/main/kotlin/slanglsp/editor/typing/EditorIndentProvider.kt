package slanglsp.editor.typing

import com.intellij.application.options.CodeStyle
import com.intellij.lang.Language
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.TokenType
import com.intellij.psi.codeStyle.joinLines.JoinedLinesSpacingCalculator
import com.intellij.psi.codeStyle.lineIndent.LineIndentProvider
import com.intellij.psi.tree.IElementType
import java.util.*
import kotlin.math.max
import slanglsp.language.SlangLanguage
import slanglsp.language.SlangLexer
import slanglsp.language.generics.GenericContext

/** Local editor indentation; document formatting remains owned by clang-format. */
class EditorIndentProvider : LineIndentProvider, JoinedLinesSpacingCalculator {
    override fun isSuitableFor(language: Language?): Boolean {
        return language != null && language.isKindOf(SlangLanguage.INSTANCE)
    }

    override fun getLineIndent(project: Project, editor: Editor, language: Language?, offset: Int): String? {
        if (!isSuitableFor(language)) {
            return null
        }

        val document = editor.document
        val text = document.immutableCharSequence
        val line = document.getLineNumber(offset)
        val start = document.getLineStartOffset(line)
        val next: Int = Indent.skipSpaces(text, offset)
        var memberAccess = false

        if (next < document.textLength) {
            val token = editor.highlighter.createIterator(next)

            if (Indent.protectedToken(token.tokenType)) {
                return LineIndentProvider.DO_NOT_ADJUST
            }

            memberAccess = token.tokenType === SlangLexer.DOT && token.end - token.start == 1
        }

        // Do not join code into a line comment or preprocessor directive.
        if (line > 0) {
            val previousStart = document.getLineStartOffset(line - 1)
            val previousEnd = document.getLineEndOffset(line - 1)

            val token = editor.highlighter.createIterator(previousStart)
            while (!token.atEnd() && token.start < previousEnd) {
                if (Indent.protectedToken(token.tokenType) && token.tokenType !== SlangLexer.STRING) {
                    return LineIndentProvider.DO_NOT_ADJUST
                }
                token.advance()
            }

            if (previousEnd > previousStart && text[previousEnd - 1] == '\\') {
                return LineIndentProvider.DO_NOT_ADJUST
            }
        }

        val options = CodeStyle.getIndentOptions(project, document)
        val tabSize = max(1, options.TAB_SIZE)
        var columns = 0
        val nextChar = if (next < text.length) text[next] else '\u0000'
        var previousChar = '\u0000'
        var inContinuation = false
        var caseBody = false
        val closers = ArrayDeque<Char?>()
        var generics: GenericContext? = null
        val token = editor.highlighter.createIterator(max(0, start - 1))

        while (start > 0 && !token.atEnd()) {
            ProgressManager.checkCanceled()
            val type = token.tokenType

            if (previousChar == '\u0000' &&
                type !== TokenType.WHITE_SPACE &&
                type !== SlangLexer.COMMENT &&
                type !== SlangLexer.BLOCK_COMMENT) {
                previousChar = text[token.end - 1]
            }

            if (type === SlangLexer.DIRECTIVE) {
                token.retreat()
                continue
            }

            if (closers.isEmpty() && type === SlangLexer.KEYWORD) {
                val word = text.subSequence(token.start, token.end).toString()
                caseBody = caseBody or (word == "case" || word == "default")
            }

            var genericAngle = false
            if (type === SlangLexer.OPERATOR && (text[token.start] == '<' || text[token.start] == '>')) {
                if (generics == null) {
                    generics = GenericResolver.context(editor)
                }
                val index = generics.tokenAt(token.start)
                genericAngle = generics.isOpening(index) || generics.isClosing(index)
            }

            if (genericAngle ||
                type === SlangLexer.LBRACE ||
                type === SlangLexer.RBRACE ||
                type === SlangLexer.LPAREN ||
                type === SlangLexer.RPAREN ||
                type === SlangLexer.LBRACKET ||
                type === SlangLexer.RBRACKET) {
                val c = text[token.start]

                if (c == '}' || c == ')' || c == ']' || c == '>') {
                    closers.push(c)
                } else if (!closers.isEmpty()) {
                    if (Indent.closing(c) != closers.pop()) {
                        return LineIndentProvider.DO_NOT_ADJUST
                    }
                } else {
                    inContinuation = c != '{'
                    columns = Indent.indentWidth(document, text, token.start, tabSize)

                    if (nextChar != Indent.closing(c)) {
                        columns += if (c == '{') options.INDENT_SIZE else options.CONTINUATION_INDENT_SIZE
                    }

                    break
                }
            }

            token.retreat()
        }

        if (!closers.isEmpty()) {
            return LineIndentProvider.DO_NOT_ADJUST
        }

        val currentLine = text.subSequence(next, document.getLineEndOffset(line)).toString()
        val caseLabel = currentLine.startsWith("case ") || currentLine.startsWith("default:")
        if (caseBody && !inContinuation && !caseLabel && nextChar != '}') {
            columns += options.INDENT_SIZE
        }

        val controlIndent: Int = Indent.controlBodyIndent(document, editor, text, start, tabSize)
        if (!inContinuation && controlIndent >= 0 && nextChar != '{' && nextChar != '}') {
            columns = controlIndent + options.INDENT_SIZE
        }

        // A trailing operator starts a continuation; retain it until the statement ends.
        val expressionContinuation =
            previousChar == '=' ||
                previousChar == '+' ||
                previousChar == '-' ||
                previousChar == '*' ||
                previousChar == '/' ||
                previousChar == '&' ||
                previousChar == '|' ||
                previousChar == '?' ||
                previousChar == ',' ||
                previousChar == ':'
        if (!inContinuation && controlIndent < 0 && expressionContinuation && !caseBody) {
            columns += options.CONTINUATION_INDENT_SIZE
        }

        if (!inContinuation &&
            !memberAccess &&
            nextChar != '{' &&
            nextChar != '}' &&
            controlIndent < 0 &&
            !expressionContinuation &&
            !caseBody &&
            previousChar != '\u0000' &&
            previousChar != ';' &&
            previousChar != '{' &&
            previousChar != '}') {
            return LineIndentProvider.DO_NOT_ADJUST
        }

        if (memberAccess && !inContinuation) {
            columns += options.CONTINUATION_INDENT_SIZE
        }

        columns = max(0, columns)

        return if (options.USE_TAB_CHARACTER) "\t".repeat(columns / tabSize) + " ".repeat(columns % tabSize)
        else " ".repeat(columns)
    }

    override fun getJoinedLinesSpacing(project: Project, editor: Editor, language: Language?, offset: Int): Int {
        if (!isSuitableFor(language)) {
            return -1
        }

        val text = editor.document.immutableCharSequence
        var previous = offset - 1

        while (previous >= 0 && Character.isWhitespace(text[previous])) {
            previous--
        }

        if (previous < 0 || offset >= text.length) {
            return 0
        }

        val left = text[previous]
        val right = text[offset]

        if (right == '.' ||
            right == ',' ||
            right == ';' ||
            right == ')' ||
            right == ']' ||
            left == '(' ||
            left == '[' ||
            left == '.') {
            return 0
        }

        return 1
    }

    private object Indent {
        fun protectedToken(type: IElementType?): Boolean {
            return type === SlangLexer.COMMENT ||
                type === SlangLexer.BLOCK_COMMENT ||
                type === SlangLexer.STRING ||
                type === SlangLexer.DIRECTIVE
        }

        fun controlBodyIndent(document: Document, editor: Editor, text: CharSequence, start: Int, tabSize: Int): Int {
            if (start == 0) {
                return -1
            }

            val token = editor.highlighter.createIterator(start - 1)
            while (!token.atEnd() &&
                (token.tokenType === TokenType.WHITE_SPACE ||
                    token.tokenType === SlangLexer.COMMENT ||
                    token.tokenType === SlangLexer.BLOCK_COMMENT)) {
                token.retreat()
            }
            if (token.atEnd()) {
                return -1
            }

            if (token.tokenType === SlangLexer.RPAREN) {
                var depth = 1
                token.retreat()
                while (!token.atEnd() && depth > 0) {
                    if (token.tokenType === SlangLexer.RPAREN) {
                        depth++
                    } else if (token.tokenType === SlangLexer.LPAREN) {
                        depth--
                    }
                    token.retreat()
                }
                while (!token.atEnd() && token.tokenType === TokenType.WHITE_SPACE) {
                    token.retreat()
                }
            }
            if (!token.atEnd() && token.tokenType === SlangLexer.KEYWORD) {
                val word = text.subSequence(token.start, token.end).toString()
                if (word == "if" || word == "for" || word == "while" || word == "else" || word == "do") {
                    return indentWidth(document, text, token.start, tabSize)
                }
            }
            return -1
        }

        fun closing(opener: Char): Char {
            return when (opener) {
                '{' -> '}'
                '(' -> ')'
                '[' -> ']'
                '<' -> '>'
                else -> '\u0000'
            }
        }

        fun skipSpaces(text: CharSequence, offset: Int): Int {
            var offset = offset
            while (offset < text.length && (text[offset] == ' ' || text[offset] == '\t')) {
                offset++
            }

            return offset
        }

        fun indentWidth(document: Document, text: CharSequence, offset: Int, tabSize: Int): Int {
            val start = document.getLineStartOffset(document.getLineNumber(offset))
            val end: Int = skipSpaces(text, start)
            var width = 0

            for (i in start..<end) {
                width = if (text[i] == '\t') (width / tabSize + 1) * tabSize else width + 1
            }

            return width
        }
    }
}

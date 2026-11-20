package slanglsp.editor.inspections.unused

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService.Companion.isDumb
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.util.PsiModificationTracker
import slanglsp.SlangBundle
import slanglsp.analysis.EditorAnalysisTasks
import slanglsp.editor.navigation.UsageSearch
import slanglsp.language.SlangLexer

/** Finds a complete declaration without crossing siblings or dropping initializer side effects. */
internal object UnusedRemoval {
    fun quickFix(input: UnusedSymbolsAnnotator.Input, candidate: UnusedSymbolsAnnotator.Candidate): IntentionAction? {
        if (range(input.text, candidate) == null) {
            return null
        }
        return RemoveUnusedFix(input, candidate)
    }

    @JvmStatic
    fun range(text: String, candidate: UnusedSymbolsAnnotator.Candidate): TextRange? {
        val lexer = SlangLexer()
        lexer.start(text)
        val tokens: MutableList<Token> = ArrayList()
        var nameIndex = -1
        while (lexer.tokenType != null) {
            val type = lexer.tokenType
            if (type !== TokenType.WHITE_SPACE && type !== SlangLexer.COMMENT && type !== SlangLexer.BLOCK_COMMENT) {
                if (lexer.tokenStart == candidate.range.startOffset) {
                    nameIndex = tokens.size
                }
                tokens.add(
                    Token(
                        text.substring(lexer.tokenStart, lexer.tokenEnd),
                        lexer.tokenStart,
                        lexer.tokenEnd,
                        type === SlangLexer.STRING))
            }
            lexer.advance()
        }
        if (nameIndex < 0 || tokens[nameIndex].text != candidate.name) {
            return null
        }
        var first = nameIndex
        while (first > 0 && !mutableSetOf<String?>(";", "{", "}").contains(tokens[first - 1].text)) {
            first--
        }
        val bodyDeclaration =
            mutableSetOf<String?>("function", "method", "struct", "class", "enum").contains(candidate.kind)
        val prefix = text.substring(tokens[first].start, candidate.range.startOffset)
        if (prefix.contains("#") ||
            prefix.contains("=") ||
            prefix.contains(",") ||
            !bodyDeclaration && (prefix.contains("(") || prefix.contains(")"))) {
            return null
        }
        var parens = 0
        var brackets = 0
        var braces = 0
        var body = false
        var initializer = false
        for (i in nameIndex + 1..<tokens.size) {
            val token = tokens[i]
            val word = token.text
            if (word == "#") {
                return null
            }
            if (word == ";" && parens == 0 && brackets == 0 && braces == 0) {
                return expandLine(text, tokens[first].start, token.end)
            }
            if (!bodyDeclaration && word == "," && parens == 0 && brackets == 0 && braces == 0) {
                return null // Removing one declarator must not remove its siblings.
            }
            if (!bodyDeclaration && word == "=") {
                initializer = true
                continue
            }
            if (initializer &&
                !token.stringLiteral &&
                !word.matches("[0-9][A-Za-z0-9_.]*|true|false|[+\\-]".toRegex())) {
                return null // Calls, reads, and overloaded expressions need a preserve-side-effects action.
            }
            when (word) {
                "(" -> parens++
                ")" -> parens--
                "[" -> brackets++
                "]" -> brackets--
                "{" -> {
                    if (!bodyDeclaration) {
                        return null
                    }
                    braces++
                    body = true
                }

                "}" -> {
                    braces--
                    if (body && braces == 0 && parens == 0 && brackets == 0) {
                        var end = token.end
                        if (i + 1 < tokens.size && tokens[i + 1].text == ";") {
                            end = tokens[i + 1].end
                        } else if (mutableSetOf<String?>("struct", "class", "enum").contains(candidate.kind) &&
                            i + 1 < tokens.size &&
                            (tokens[i + 1].text != "}")) {
                            if (!text.substring(end, tokens[i + 1].start).contains("\n") ||
                                mutableSetOf<String?>("*", "&", "(").contains(tokens[i + 1].text) ||
                                i + 2 < tokens.size &&
                                    mutableSetOf<String?>(";", "=", ",", "[").contains(tokens[i + 2].text)) {
                                return null
                            }
                        }
                        return expandLine(text, tokens[first].start, end)
                    }
                }

                else -> {}
            }
            if (parens < 0 || brackets < 0 || braces < 0) {
                return null
            }
        }
        return null
    }

    private fun expandLine(text: String, start: Int, end: Int): TextRange {
        val lineStart = text.lastIndexOf('\n', start - 1) + 1
        val newline = text.indexOf('\n', end)
        val lineEnd = if (newline < 0) text.length else newline
        if (text.substring(lineStart, start).isBlank() && text.substring(end, lineEnd).isBlank()) {
            return TextRange(lineStart, if (newline < 0) lineEnd else newline + 1)
        }
        return TextRange(start, end)
    }

    @JvmRecord private data class Token(val text: String, val start: Int, val end: Int, val stringLiteral: Boolean)
}

private class RemoveUnusedFix(
    private val input: UnusedSymbolsAnnotator.Input,
    private val candidate: UnusedSymbolsAnnotator.Candidate
) : IntentionAction {
    override fun getText(): String {
        return candidate.removalText()
    }

    override fun getFamilyName(): String {
        return SlangBundle.message("unused.remove")
    }

    private fun current(): Boolean {
        return input.file.isValid &&
            input.file.isWritable &&
            input.document.isWritable &&
            input.document.modificationStamp == input.stamp &&
            PsiModificationTracker.getInstance(input.file.project).modificationCount == input.projectStamp
    }

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        return file === input.file && current() && !isDumb(project)
    }

    override fun startInWriteAction(): Boolean {
        return false
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val range = UnusedRemoval.range(input.text, candidate)
        if (range == null || !current()) {
            return
        }
        project.service<EditorAnalysisTasks>().launch(
            text,
            compute = {
                val element = runReadActionBlocking {
                    if (current()) {
                        input.file.findElementAt(candidate.range.startOffset)
                    } else {
                        null
                    }
                }
                element != null && UsageSearch.isUnused(element)
            }) { unused ->
                if (unused) {
                    WriteCommandAction.runWriteCommandAction(
                        project,
                        SlangBundle.message("unused.remove.command"),
                        null,
                        {
                            if (current()) {
                                input.document.deleteString(range.startOffset, range.endOffset)
                                PsiDocumentManager.getInstance(project).commitAllDocuments()
                            }
                        })
                }
                if (input.file.isValid) {
                    DaemonCodeAnalyzer.getInstance(project).settingsChanged()
                }
            }
    }
}

package slanglsp.editor.documentation

import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.TokenType
import java.util.regex.Matcher
import slanglsp.SlangBundle
import slanglsp.language.SlangLexer
import slanglsp.server.BuiltinFiles

/** Recovers standard-library comments when slangd only supplies a signature. */
internal object BuiltinHoverDocumentation {
    fun enrich(project: Project, markdown: String): String {
        val location = BUILTIN_LOCATION.find(markdown) ?: return markdown
        val prefix = markdown.substring(0, location.range.first)
        if (!Comments.lacksExplanation(prefix)) {
            return markdown
        }

        val (module, lineNumber) = location.destructured
        val line = lineNumber.toIntOrNull() ?: return markdown
        val builtin = project.service<BuiltinFiles>().resolve("slang-synth://$module/$module.builtin")
            ?: return markdown
        val documentation = documentationAtLine(builtin.content, line)
        if (documentation.isBlank()) {
            return markdown
        }

        return "${prefix.trimEnd()}\n\n$documentation\n\n${markdown.substring(location.range.first)}"
    }

    private val BUILTIN_LOCATION = Regex("""(?m)^Defined in (core|glsl)\(([1-9][0-9]*)\)\s*$""")

    fun documentationAtLine(source: CharSequence, line: Int): String {
        return Comments.atLine(source, line)
    }

    private object Comments {
        fun lacksExplanation(prefix: String): Boolean {
            // Keep documentation supplied by newer servers, including its formatting.
            return prefix
                .replaceFirst("(?s)\\A```[^\\n]*\\n.*?```".toRegex(), "")
                .replace("(?m)^(Forward|Backward) derivative:.*$".toRegex(), "")
                .isBlank()
        }

        fun atLine(source: CharSequence, line: Int): String {
            if (line < 1) {
                return ""
            }
            var offset = 0

            var current = 1
            while (current < line) {
                if (offset >= source.length) {
                    return ""
                }
                if (source[offset] == '\n') {
                    current++
                }
                offset++
            }

            if (offset >= source.length) {
                return ""
            }
            val lexer = SlangLexer()
            lexer.start(source)
            val comment = StringBuilder()
            var previousDocLine = false

            while (lexer.tokenType != null && lexer.tokenStart < offset) {
                ProgressManager.checkCanceled()
                val type = lexer.tokenType
                val token = lexer.tokenText
                if (type === SlangLexer.BLOCK_COMMENT && token.startsWith("/**")) {
                    comment.setLength(0)
                    comment.append(
                        token
                            .replaceFirst("^/\\*+".toRegex(), "")
                            .replaceFirst("\\*+/$".toRegex(), "")
                            .replace("(?m)^\\h*\\* ?".toRegex(), ""))
                    previousDocLine = false
                } else if (type === SlangLexer.COMMENT && token.startsWith("///")) {
                    if (!previousDocLine) {
                        comment.setLength(0)
                    }
                    comment.append(token.substring(3).trimStart()).append('\n')
                    previousDocLine = true
                } else if (type !== TokenType.WHITE_SPACE) {
                    previousDocLine = false

                    // Never borrow docs from a preceding declaration or enclosing type.
                    if (type === SlangLexer.SEMICOLON ||
                        type === SlangLexer.LBRACE ||
                        type === SlangLexer.RBRACE ||
                        type === SlangLexer.COMMENT ||
                        type === SlangLexer.BLOCK_COMMENT ||
                        type === SlangLexer.DIRECTIVE) {
                        comment.setLength(0)
                    }
                }

                lexer.advance()
            }

            return renderComment(comment.toString())
        }

        private fun renderComment(comment: String): String {
            return comment
                .replace(
                    "(?m)^\\h*@param\\s+(\\w+)\\h*".toRegex(),
                    "\n**" + Matcher.quoteReplacement(SlangBundle.message("documentation.parameter")) + " `$1`:** ")
                .replace(
                    "(?m)^\\h*@returns?\\h*".toRegex(),
                    Matcher.quoteReplacement("\n**" + SlangBundle.message("documentation.returns") + ":** "))
                .replace(
                    "(?m)^\\h*@remarks\\h*".toRegex(),
                    Matcher.quoteReplacement("\n**" + SlangBundle.message("documentation.remarks") + "**\n\n"))
                .replace(
                    "(?m)^\\h*@see\\h*".toRegex(),
                    Matcher.quoteReplacement("\n**" + SlangBundle.message("documentation.see.also") + ":** "))
                .replace("(?m)^\\h*@category[^\\n]*".toRegex(), "")
                .trim()
        }
    }
}

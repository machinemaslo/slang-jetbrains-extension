package slanglsp.server.client

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.redhat.devtools.lsp4ij.client.features.LSPHoverFeature
import java.util.regex.Matcher
import java.util.regex.Pattern
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import slanglsp.SlangBundle
import slanglsp.language.SlangLexer
import slanglsp.server.BuiltinFiles

/** Recovers standard-library documentation through LSP4IJ's experimental hover extension. */
@Suppress("UnstableApiUsage")
internal class HoverFeature : LSPHoverFeature() {
    override fun getContent(content: MarkupContent, file: PsiFile): String? {
        var content = content
        if (MarkupKind.MARKDOWN == content.kind) {
            val markdown = content.value
            val location: Matcher = BUILTIN_LOCATION.matcher(markdown)

            if (location.find() && lacksExplanation(markdown.substring(0, location.start()))) {
                val line: Int

                try {
                    line = location.group(2).toInt()
                } catch (_: NumberFormatException) {
                    return super.getContent(content, file)
                }

                val module = location.group(1)
                val builtin =
                    file.project
                        .getService<BuiltinFiles?>(BuiltinFiles::class.java)!!
                        .resolve("slang-synth://$module/$module.builtin")

                if (builtin != null) {
                    val documentation: String = documentationAtLine(builtin.content, line)

                    if (documentation.isNotBlank()) {
                        content =
                            MarkupContent(
                                MarkupKind.MARKDOWN,
                                markdown.substring(0, location.start()).trimEnd() +
                                    "\n\n" +
                                    documentation +
                                    "\n\n" +
                                    markdown.substring(location.start()))
                    }
                }
            }
        }

        return super.getContent(content, file)
    }

    companion object {
        private val BUILTIN_LOCATION: Pattern = Pattern.compile("(?m)^Defined in (core|glsl)\\(([1-9][0-9]*)\\)\\s*$")

        private fun lacksExplanation(prefix: String): Boolean {
            // Keep documentation supplied by newer servers, including its formatting.
            return prefix
                .replaceFirst("(?s)\\A```[^\\n]*\\n.*?```".toRegex(), "")
                .replace("(?m)^(Forward|Backward) derivative:.*$".toRegex(), "")
                .isBlank()
        }

        @JvmStatic
        fun documentationAtLine(source: CharSequence, line: Int): String {
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

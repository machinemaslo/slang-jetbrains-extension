package slanglsp.editor.navigation.cpp

import com.intellij.openapi.progress.ProgressManager

internal data class SourceToken(val text: String, val offset: Int)

internal fun sourceTokens(source: CharSequence): List<SourceToken> =
    tokenPattern.findAll(source).mapNotNull { match ->
        ProgressManager.checkCanceled()
        val text = match.value.trimStart()
        if (text.startsWith("//") || text.startsWith("/*")) null else SourceToken(text, match.range.first)
    }.toList()

private val tokenPattern = Regex(
    """(?m:^[ \t]*#(?:[^\n]*\\\n)*[^\n]*)|//(?:[^\n]*\\\n)*[^\n]*|(?s:/\*.*?(?:\*/|\z))|""" +
        """(?s:(?:u8|u|U|L)?R"([^\s()\\]{0,16})\(.*?(?:\)\1"|\z))|""" +
        "\"(?:\\\\[\\s\\S]|[^\"\\\\\\r\\n])*+\"?|'(?:\\\\[\\s\\S]|[^'\\\\\\r\\n])*+'?|" +
        """[A-Za-z_][A-Za-z_0-9]*|[0-9][A-Za-z_0-9]*|::|->|==|!=|&&|\|\||[^\s]""",
)

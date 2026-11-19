package slanglsp.editor.spellchecking

import com.intellij.codeInspection.SuppressionUtil
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.spellchecker.inspections.PlainTextSplitter
import com.intellij.spellchecker.inspections.SpellCheckingInspection.SpellCheckingScope
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy
import com.intellij.spellchecker.tokenizer.TokenConsumer
import com.intellij.spellchecker.tokenizer.Tokenizer
import slanglsp.language.SlangLexer

/** Uses the IDE's dictionaries, suggestions and Save to dictionary actions. */
class TokenSpellcheckingStrategy : SpellcheckingStrategy() {
    override fun elementFitsScope(element: PsiElement, scope: MutableSet<SpellCheckingScope?>): Boolean {
        val type = element.node.elementType
        if (type === SlangLexer.STRING) {
            return scope.contains(SpellCheckingScope.Literals)
        }
        return (type === SlangLexer.COMMENT || type === SlangLexer.BLOCK_COMMENT) &&
            scope.contains(SpellCheckingScope.Comments)
    }

    override fun getTokenizer(element: PsiElement, scope: MutableSet<SpellCheckingScope?>): Tokenizer<*> {
        return if (elementFitsScope(element, scope)) getTokenizer(element) else EMPTY_TOKENIZER
    }

    override fun getTokenizer(element: PsiElement?): Tokenizer<*> {
        if (isInjectedLanguageFragment(element)) {
            return EMPTY_TOKENIZER
        }
        val type = element!!.node.elementType
        if (type === SlangLexer.STRING) {
            return StringTokens.tokenizer
        }
        if (type === SlangLexer.COMMENT || type === SlangLexer.BLOCK_COMMENT) {
            return if (SuppressionUtil.isSuppressionComment(element)) EMPTY_TOKENIZER else TEXT_TOKENIZER
        }
        return EMPTY_TOKENIZER
    }

    private object StringTokens {
        val tokenizer: Tokenizer<PsiElement?> =
            object : Tokenizer<PsiElement?>() {
                override fun tokenize(element: PsiElement, consumer: TokenConsumer) {
                    val text = element.text
                    var end = text.length
                    if (end > 1 && text[end - 1] == text[0]) {
                        end--
                    }
                    var start = 1
                    var i = start
                    while (i < end) {
                        if (text[i] != '\\') {
                            i++
                            continue
                        }
                        consume(element, text, start, i, consumer)
                        i++
                        if (i < end) {
                            val escape = text[i++]
                            val digits =
                                if (escape == 'u') 4
                                else if (escape == 'U') 8 else if (escape == 'x') Int.MAX_VALUE else 0
                            if (escape in '0'..'7') {
                                var count = 0
                                while (count < 2 && i < end && text[i] >= '0' && text[i] <= '7') {
                                    i++
                                    count++
                                }
                            } else {
                                var count = 0
                                while (count < digits && i < end && (text[i].digitToIntOrNull(16) ?: -1) >= 0) {
                                    i++
                                    count++
                                }
                            }
                        }
                        start = i
                    }
                    consume(element, text, start, end, consumer)
                }
            }

        private fun consume(element: PsiElement, text: String, start: Int, end: Int, consumer: TokenConsumer) {
            if (start < end) {
                consumer.consumeToken(element, text, false, 0, TextRange(start, end), PlainTextSplitter.getInstance())
            }
        }
    }
}

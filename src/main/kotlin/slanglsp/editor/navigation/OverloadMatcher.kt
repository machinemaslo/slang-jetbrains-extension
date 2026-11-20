package slanglsp.editor.navigation

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.util.PsiTreeUtil
import com.redhat.devtools.lsp4ij.LSPIJUtils
import com.redhat.devtools.lsp4ij.LanguageServerItem
import com.redhat.devtools.lsp4ij.features.documentation.LSPDocumentationHelper
import java.util.regex.Pattern
import org.eclipse.lsp4j.*
import slanglsp.editor.documentation.HoverRequests
import slanglsp.language.SlangLexer

/** Uses the server's selected signature, rather than reimplementing Slang overload resolution. */
internal object OverloadMatcher {
    private val CODE_BLOCK: Pattern = Pattern.compile("(?s)```[^\\r\\n]*\\R(.*?)```")
    private val FUNCTION_SIGNATURE: Pattern =
        Pattern.compile(
            "^(?:(?:public|private|internal|static|export|extern|inline|mutating|nonmutating|differentiable)\\s+)*func\\s+")

    @JvmStatic
    fun isFunctionDeclaration(file: PsiFile, range: Range): Boolean {
        val document = LSPIJUtils.getDocument(file) ?: return false

        val name = file.findElementAt(LSPIJUtils.toOffset(range.start, document))
        val previous = codeLeaf(name, false)
        var next = codeLeaf(name, true)

        if (previous == null || next == null) {
            return false
        }

        val kind = previous.node.elementType
        val returnType =
            kind === SlangLexer.BUILTIN_TYPE ||
                kind === SlangLexer.TYPE ||
                kind === SlangLexer.VARIABLE ||
                previous.textMatches("void") ||
                previous.textMatches(">")

        if (!returnType) {
            return false
        }

        // Skip generic parameters before the ordinary parameter list.
        if (next.textMatches("<")) {
            next = afterBalanced(next, "<", ">")
        }

        if (next == null || !next.textMatches("(")) {
            return false
        }

        val afterParameters = afterBalanced(next, "(", ")")

        return afterParameters != null &&
            (afterParameters.textMatches("{") ||
                afterParameters.textMatches(";") ||
                afterParameters.textMatches(":") ||
                afterParameters.textMatches("throws"))
    }

    private fun afterBalanced(start: PsiElement, open: String, close: String): PsiElement? {
        var depth = 0

        var token: PsiElement? = start
        while (token != null) {
            ProgressManager.checkCanceled()

            if (token.textMatches(open)) {
                depth++
            } else if (token.textMatches(close) && --depth == 0) {
                return codeLeaf(token, true)
            }
            token = codeLeaf(token, true)
        }

        return null
    }

    private fun codeLeaf(element: PsiElement?, forward: Boolean): PsiElement? {
        var leaf = element

        while (leaf != null) {
            leaf = if (forward) PsiTreeUtil.nextLeaf(leaf) else PsiTreeUtil.prevLeaf(leaf)

            if (leaf != null) {
                val kind = leaf.node.elementType

                if (kind !== TokenType.WHITE_SPACE &&
                    kind !== SlangLexer.COMMENT &&
                    kind !== SlangLexer.BLOCK_COMMENT) {
                    return leaf
                }
            }
        }

        return null
    }

    fun resolve(
        source: PsiFile,
        offset: Int,
        target: PsiFile,
        name: String,
        declarations: List<Range>,
        server: LanguageServerItem
    ): Range? {
        val sourceDocument = runReadActionBlocking { LSPIJUtils.getDocument(source) }
        val targetDocument = runReadActionBlocking { LSPIJUtils.getDocument(target) }

        if (sourceDocument == null || targetDocument == null) {
            return null
        }

        val sourceStamp = sourceDocument.modificationStamp
        val targetStamp = targetDocument.modificationStamp
        val selected = signatureAt(source, offset, name, server) ?: return null

        var match: Range? = null

        for (declaration in declarations) {
            ProgressManager.checkCanceled()
            val declarationOffset = runReadActionBlocking {
                LSPIJUtils.toOffset(declaration.start, targetDocument)
            }
            val signature =
                signatureAt(target, declarationOffset, name, server)
                    ?: // An unknown candidate might have the same signature as the apparent match.
                    return null

            if (selected == signature) {
                if (match != null) {
                    return null
                }

                match = declaration
            }
        }

        if (sourceStamp != sourceDocument.modificationStamp || targetStamp != targetDocument.modificationStamp) {
            throw ProcessCanceledException()
        }

        return match
    }

    @JvmStatic
    fun signature(markdown: String, name: String): String? {
        val blocks = CODE_BLOCK.matcher(markdown)
        var result: String? = null

        while (blocks.find()) {
            val candidate = canonicalSignature(blocks.group(1), name)

            if (candidate != null) {
                if (result != null) {
                    return null
                }

                result = candidate
            }
        }

        return result ?: canonicalSignature(markdown, name)
    }

    private fun canonicalSignature(text: String, name: String): String? {
        val code = text.trim()

        if (!FUNCTION_SIGNATURE.matcher(code).find() ||
            !Pattern.compile("\\b" + Pattern.quote(name) + "\\s*[(<]").matcher(code).find()) {
            return null
        }

        val lexer = SlangLexer()
        lexer.start(code)
        val tokens = StringBuilder()
        var functions = 0

        while (lexer.tokenType != null) {
            val type = lexer.tokenType
            val token = code.substring(lexer.tokenStart, lexer.tokenEnd)

            if (token == "func") {
                functions++
            }

            if (type !== TokenType.WHITE_SPACE && type !== SlangLexer.COMMENT && type !== SlangLexer.BLOCK_COMMENT) {
                tokens.append(token).append('\u0000')
            }

            lexer.advance()
        }

        return if (functions == 1) tokens.toString() else null
    }

    private fun signatureAt(file: PsiFile, offset: Int, name: String, expectedServer: LanguageServerItem): String? {
        val position =
            runReadActionBlocking {
                LSPIJUtils.getDocument(file)?.let { LSPIJUtils.toPosition(offset, it) }
            } ?: return null

        val requests = HoverRequests(file)
        try {
            val server = requests.connectedServers().firstOrNull { it.server === expectedServer.server } ?: return null
            val hover = requests.requestHover(server, position) ?: return null
            return LSPDocumentationHelper.getValidMarkupContents(hover)
                .mapNotNull { signature(it.value, name) }
                .singleOrNull()
        } finally {
            requests.cancel()
        }
    }
}

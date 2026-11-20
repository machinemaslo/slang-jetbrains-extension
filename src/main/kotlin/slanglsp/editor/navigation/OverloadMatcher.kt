package slanglsp.editor.navigation

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.util.PsiTreeUtil
import java.util.regex.Pattern
import org.eclipse.lsp4j.Range
import slanglsp.analysis.AnalysisDeadline
import slanglsp.language.SlangLexer
import slanglsp.server.nativeclient.NativeNavigationRequests
import slanglsp.server.protocol.protocolOffset

/** Uses the server's selected signature, rather than reimplementing Slang overload resolution. */
internal object OverloadMatcher {
    private val CODE_BLOCK: Pattern = Pattern.compile("(?s)```[^\\r\\n]*\\R(.*?)```")
    private val FUNCTION_SIGNATURE: Pattern =
        Pattern.compile(
            "^(?:(?:public|private|internal|static|export|extern|inline|mutating|nonmutating|differentiable)\\s+)*func\\s+")

    @JvmStatic
    fun isFunctionDeclaration(file: PsiFile, range: Range): Boolean {
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return false

        val name = file.findElementAt(document.protocolOffset(range.start))
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
        verify: Boolean = false,
    ): Range? {
        val documents = PsiDocumentManager.getInstance(source.project)
        val sourceDocument = runReadActionBlocking { documents.getDocument(source) }
        val targetDocument = runReadActionBlocking { documents.getDocument(target) }

        if (sourceDocument == null || targetDocument == null) {
            return null
        }

        val sourceStamp = sourceDocument.modificationStamp
        val targetStamp = targetDocument.modificationStamp
        val selected = signatureAt(source, sourceDocument, offset, name, verify) ?: return null

        var match: Range? = null

        for (declaration in declarations) {
            ProgressManager.checkCanceled()
            val declarationOffset = runReadActionBlocking {
                targetDocument.protocolOffset(declaration.start)
            }
            val signature =
                signatureAt(target, targetDocument, declarationOffset, name, verify)
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

    private fun signatureAt(
        file: PsiFile,
        document: Document,
        offset: Int,
        name: String,
        verify: Boolean,
    ): String? {
        val virtualFile = file.virtualFile ?: return null
        val request = file.project.service<NativeNavigationRequests>().hover(virtualFile, document, offset, verify)
        try {
            return AnalysisDeadline.await(request).orEmpty().mapNotNull { signature(it.text, name) }.singleOrNull()
        } finally {
            if (!request.isDone) {
                request.cancel(true)
            }
        }
    }
}

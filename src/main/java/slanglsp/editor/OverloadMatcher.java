package slanglsp.editor;

import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.util.ProgressIndicatorUtils;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import com.intellij.psi.util.PsiTreeUtil;
import com.redhat.devtools.lsp4ij.LSPIJUtils;
import com.redhat.devtools.lsp4ij.LanguageServerItem;
import com.redhat.devtools.lsp4ij.features.documentation.LSPDocumentationHelper;
import com.redhat.devtools.lsp4ij.features.documentation.LSPHoverSupport;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import slanglsp.language.SlangLexer;

import java.util.List;
import java.util.regex.Pattern;

/** Uses the server's selected signature, rather than reimplementing Slang overload resolution. */
final class OverloadMatcher {
    private static final Pattern CODE_BLOCK = Pattern.compile("(?s)```[^\\r\\n]*\\R(.*?)```");
    private static final Pattern FUNCTION_SIGNATURE = Pattern.compile(
            "^(?:(?:public|private|internal|static|export|extern|inline|mutating|nonmutating|differentiable)\\s+)*func\\s+");

    static boolean isFunctionDeclaration(PsiFile file, Range range) {
        var document = LSPIJUtils.getDocument(file);

        if (document == null) {
            return false;
        }

        var name = file.findElementAt(LSPIJUtils.toOffset(range.getStart(), document));
        var previous = codeLeaf(name, false);
        var next = codeLeaf(name, true);

        if (previous == null || next == null) {
            return false;
        }

        var kind = previous.getNode().getElementType();
        boolean returnType = kind == SlangLexer.BUILTIN_TYPE || kind == SlangLexer.TYPE
                || kind == SlangLexer.VARIABLE || previous.textMatches("void") || previous.textMatches(">");

        if (!returnType) {
            return false;
        }

        // Skip generic parameters before the ordinary parameter list.
        if (next.textMatches("<")) {
            next = afterBalanced(next, "<", ">");
        }

        if (next == null || !next.textMatches("(")) {
            return false;
        }

        var afterParameters = afterBalanced(next, "(", ")");

        return afterParameters != null
                && (afterParameters.textMatches("{")
                    || afterParameters.textMatches(";")
                    || afterParameters.textMatches(":")
                    || afterParameters.textMatches("throws"));
    }

    private static PsiElement afterBalanced(PsiElement start, String open, String close) {
        int depth = 0;

        for (var token = start; token != null; token = codeLeaf(token, true)) {
            ProgressManager.checkCanceled();

            if (token.textMatches(open)) {
                depth++;
            } else if (token.textMatches(close) && --depth == 0) {
                return codeLeaf(token, true);
            }
        }

        return null;
    }

    private static PsiElement codeLeaf(PsiElement element, boolean forward) {
        var leaf = element;

        while (leaf != null) {
            leaf = forward ? PsiTreeUtil.nextLeaf(leaf) : PsiTreeUtil.prevLeaf(leaf);

            if (leaf != null) {
                var kind = leaf.getNode().getElementType();

                if (kind != TokenType.WHITE_SPACE && kind != SlangLexer.COMMENT && kind != SlangLexer.BLOCK_COMMENT) {
                    return leaf;
                }
            }
        }

        return null;
    }

    static Range resolve(
            PsiFile source, int offset, PsiFile target, String name,
            List<Range> declarations, LanguageServerItem server) {
        var sourceDocument = ReadActions.compute(() -> LSPIJUtils.getDocument(source));
        var targetDocument = ReadActions.compute(() -> LSPIJUtils.getDocument(target));

        if (sourceDocument == null || targetDocument == null) {
            return null;
        }

        long sourceStamp = sourceDocument.getModificationStamp();
        long targetStamp = targetDocument.getModificationStamp();
        String selected = new SignatureRequest(source).signatureAt(offset, name, server);

        if (selected == null) {
            return null;
        }

        Range match = null;

        for (var declaration : declarations) {
            ProgressManager.checkCanceled();
            int declarationOffset = ReadActions.compute(
                    () -> LSPIJUtils.toOffset(declaration.getStart(), targetDocument));
            String signature = new SignatureRequest(target).signatureAt(declarationOffset, name, server);

            if (signature == null) {
                // An unknown candidate might have the same signature as the apparent match.
                return null;
            }

            if (selected.equals(signature)) {
                if (match != null) {
                    return null;
                }

                match = declaration;
            }
        }

        if (sourceStamp != sourceDocument.getModificationStamp()
                || targetStamp != targetDocument.getModificationStamp()) {
            throw new ProcessCanceledException();
        }

        return match;
    }

    static String signature(String markdown, String name) {
        var blocks = CODE_BLOCK.matcher(markdown);
        String result = null;

        while (blocks.find()) {
            String candidate = canonicalSignature(blocks.group(1), name);

            if (candidate != null) {
                if (result != null) {
                    return null;
                }

                result = candidate;
            }
        }

        return result != null ? result : canonicalSignature(markdown, name);
    }

    private static String canonicalSignature(String text, String name) {
        String code = text.strip();

        if (!FUNCTION_SIGNATURE.matcher(code).find()
                || !Pattern.compile("\\b" + Pattern.quote(name) + "\\s*[(<]").matcher(code).find()) {
            return null;
        }

        var lexer = new SlangLexer();
        lexer.start(code);
        var tokens = new StringBuilder();
        int functions = 0;

        while (lexer.getTokenType() != null) {
            var type = lexer.getTokenType();
            String token = code.substring(lexer.getTokenStart(), lexer.getTokenEnd());

            if (token.equals("func")) {
                functions++;
            }

            if (type != TokenType.WHITE_SPACE && type != SlangLexer.COMMENT && type != SlangLexer.BLOCK_COMMENT) {
                tokens.append(token).append('\0');
            }

            lexer.advance();
        }

        return functions == 1 ? tokens.toString() : null;
    }

    // Use the document-feature API to synchronize unsaved text before asking the same server.
    private static final class SignatureRequest extends LSPHoverSupport {
        SignatureRequest(PsiFile file) {
            super(file);
        }

        String signatureAt(int offset, String name, LanguageServerItem expectedServer) {
            PsiFile file = getFile();
            var position = ReadActions.compute(() -> {
                var document = LSPIJUtils.getDocument(file);

                return document == null ? null : LSPIJUtils.toPosition(offset, document);
            });

            if (position == null) {
                return null;
            }

            try {
                var servers = ProgressIndicatorUtils.awaitWithCheckCanceled(getLanguageServers(file,
                        features -> features.getHoverFeature().isEnabled(file),
                        features -> features.getHoverFeature().isSupported(file)));

                for (var server : servers) {
                    if (server.getServer() != expectedServer.getServer()) {
                        continue;
                    }

                    var identifier = new TextDocumentIdentifier();
                    updateTextDocumentUri(identifier, file, server);
                    var request = server.getTextDocumentService().hover(new HoverParams(identifier, position));

                    try {
                        var hover = ProgressIndicatorUtils.awaitWithCheckCanceled(request);

                        if (hover == null) {
                            return null;
                        }

                        var signatures = LSPDocumentationHelper.getValidMarkupContents(hover).stream()
                                .map(content -> signature(content.getValue(), name))
                                .filter(java.util.Objects::nonNull)
                                .toList();

                        return signatures.size() == 1 ? signatures.getFirst() : null;
                    } finally {
                        if (!request.isDone()) {
                            request.cancel(true);
                        }
                    }
                }

                return null;
            } finally {
                cancel();
            }
        }
    }
}

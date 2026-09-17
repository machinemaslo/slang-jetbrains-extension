package slanglsp;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.util.ProgressIndicatorUtils;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.redhat.devtools.lsp4ij.LSPFileSupport;
import com.redhat.devtools.lsp4ij.LSPIJUtils;
import com.redhat.devtools.lsp4ij.features.LSPPsiElementFactory;
import com.redhat.devtools.lsp4ij.features.navigation.LSPGotoDeclarationHandler;
import com.redhat.devtools.lsp4ij.features.references.LSPReferenceParams;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.TextDocumentIdentifier;

import java.util.Arrays;
import java.util.Objects;

/** Slang-only declaration/usage navigation, leaving other languages to their own handlers. */
public class SlangGotoDeclarationHandler implements GotoDeclarationHandler {
    @Override
    public PsiElement[] getGotoDeclarationTargets(PsiElement source, int offset, Editor editor) {
        if (source == null || source.getContainingFile() == null
                || source.getContainingFile().getLanguage() != SlangLanguage.INSTANCE) return null;
        var type = source.getNode() == null ? null : source.getNode().getElementType();
        boolean module = type == SlangLexer.MODULE_NAME;
        if (!module && type != SlangLexer.TYPE && type != SlangLexer.FUNCTION && type != SlangLexer.VARIABLE) return null;
        // slangd resolves a dotted import at its final component.
        int position = module ? source.getTextRange().getEndOffset() - 1 : offset;
        PsiElement[] targets = definitions(source, position);
        if (module) {
            return Arrays.stream(targets).map(PsiElement::getContainingFile)
                    .filter(Objects::nonNull).distinct().toArray(PsiElement[]::new);
        }
        if (targets.length > 0 && Arrays.stream(targets).anyMatch(target -> !isSource(target, source, offset))) {
            return targets;
        }
        // A declaration resolving to itself should offer its callers/usages, not jump in place.
        return Arrays.stream(references(source, offset, editor))
                .filter(target -> !isSource(target, source, offset)).toArray(PsiElement[]::new);
    }

    private static boolean isSource(PsiElement target, PsiElement source, int offset) {
        PsiFile targetFile = target.getContainingFile();
        PsiFile sourceFile = source.getContainingFile();
        boolean sameFile = targetFile == sourceFile || (targetFile != null && sourceFile != null
                && targetFile.getVirtualFile() != null
                && targetFile.getVirtualFile().equals(sourceFile.getVirtualFile()));
        return sameFile && target.getTextRange() != null && target.getTextRange().containsOffset(offset);
    }

    protected PsiElement[] definitions(PsiElement source, int offset) {
        return LSPGotoDeclarationHandler.getGotoDeclarationTargets(source, offset);
    }

    protected PsiElement[] references(PsiElement source, int offset, Editor editor) {
        PsiFile file = source.getContainingFile();
        var params = new LSPReferenceParams(new TextDocumentIdentifier(),
                LSPIJUtils.toPosition(offset, editor.getDocument()), offset);
        params.setContext(new ReferenceContext(false));
        var future = LSPFileSupport.getSupport(file).getReferenceSupport().getReferences(params);
        var locations = ProgressIndicatorUtils.awaitWithCheckCanceled(future);
        if (locations == null) return PsiElement.EMPTY_ARRAY;
        return locations.stream().map(location -> LSPPsiElementFactory.toPsiElement(
                        location.location(), location.languageServer().getClientFeatures(), file.getProject()))
                .filter(Objects::nonNull).toArray(PsiElement[]::new);
    }
}

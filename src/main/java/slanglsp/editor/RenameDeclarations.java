package slanglsp.editor;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.util.ProgressIndicatorUtils;
import com.intellij.psi.PsiFile;
import com.redhat.devtools.lsp4ij.LSPIJUtils;
import com.redhat.devtools.lsp4ij.features.documentSymbol.LSPDocumentSymbolSupport;
import org.eclipse.lsp4j.*;

import java.util.LinkedHashMap;

/** Declaration-site definition requests are optional in slangd; document symbols are independent evidence. */
final class RenameDeclarations {
    static boolean contains(PsiFile file, Document document, String text, String name, int offset, Runnable check) {
        var support = new LSPDocumentSymbolSupport(file);
        try {
            var future = ReadActions.compute(() -> {
                check.run();
                return support.getDocumentSymbols(new DocumentSymbolParams(
                        new TextDocumentIdentifier(UsageSearch.fileUri(file.getVirtualFile()).toString())));
            });
            var symbols = ProgressIndicatorUtils.awaitWithCheckCanceled(future);
            return ReadActions.compute(() -> {
                check.run();
                return symbols != null && symbols.stream().anyMatch(symbol ->
                        contains(symbol.getDocumentSymbol(), document, text, name, offset));
            });
        } finally {
            support.cancel();
        }
    }

    private static boolean contains(DocumentSymbol symbol, Document document, String text, String name, int offset) {
        if (symbol == null || symbol.getSelectionRange() == null || symbol.getRange() == null) {
            return false;
        }
        int start = LSPIJUtils.toOffset(symbol.getSelectionRange().getStart(), document);
        int end = start + symbol.getName().length();
        if (start < 0 || end > text.length() || !text.substring(start, end).equals(symbol.getName())) {
            return false;
        }
        if (start == offset && name.equals(symbol.getName())) {
            return true;
        }
        int bodyEnd = Math.min(text.length(), LSPIJUtils.toOffset(symbol.getRange().getEnd(), document));
        if (bodyEnd > end) {
            if (symbol.getKind() == SymbolKind.Function || symbol.getKind() == SymbolKind.Method) {
                if (UnusedDeclarations.locals(text, end, bodyEnd).stream()
                        .anyMatch(local -> local.range().getStartOffset() == offset && local.name().equals(name))) {
                    return true;
                }
            }
            if (symbol.getKind() == SymbolKind.Function || symbol.getKind() == SymbolKind.Method
                    || symbol.getKind() == SymbolKind.Struct || symbol.getKind() == SymbolKind.Class
                    || symbol.getKind() == SymbolKind.Interface) {
                var parameters = new LinkedHashMap<Integer, NamingAnnotator.Candidate>();
                NamingParameters.collect(text, end, bodyEnd, parameters);
                var parameter = parameters.get(offset);
                if (parameter != null && parameter.name().equals(name)) {
                    return true;
                }
            }
        }
        return symbol.getChildren() != null && symbol.getChildren().stream()
                .anyMatch(child -> contains(child, document, text, name, offset));
    }
}

package slanglsp.editor;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.indexing.FileBasedIndex;

import java.util.HashMap;
import java.util.Map;

/** Indexed, local declaration lookup: typing never waits for a language-server request. */
final class GenericResolver {
    private GenericResolver() {
    }

    static GenericContext context(Editor editor) {
        return context(editor, editor.getDocument().getImmutableCharSequence());
    }

    static GenericContext context(Editor editor, CharSequence text) {
        var project = editor.getProject();
        if (project == null || DumbService.isDumb(project)) {
            return new GenericContext(text);
        }
        var contexts = new GenericContext[1];
        Map<String, Boolean> resolved = new HashMap<>();
        contexts[0] = new GenericContext(text, (name, owner) -> resolved.computeIfAbsent(
                name + ":" + owner, ignored -> {
                    var imports = contexts[0].imports();
                    if (imports.isEmpty()) {
                        return false;
                    }
                    try {
                        String symbol = GenericDeclarationsIndex.symbol(owner, name);
                        var index = FileBasedIndex.getInstance();
                        for (String module : imports) {
                            if (!index.getContainingFiles(GenericDeclarationsIndex.NAME,
                                    GenericDeclarationsIndex.key(module, symbol),
                                    GlobalSearchScope.allScope(project)).isEmpty()) {
                                return true;
                            }
                        }
                    } catch (IndexNotReadyException indexing) {
                        return false;
                    }
                    return false;
                }));
        return contexts[0];
    }
}

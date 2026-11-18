package slanglsp.editor;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.util.Key;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.indexing.FileBasedIndex;

import java.util.HashMap;
import java.util.Map;

/** Indexed, local declaration lookup: typing never waits for a language-server request. */
final class GenericResolver {
    private static final Key<Parsed> PARSED = Key.create("slang.generics.parsed");

    private record Parsed(long stamp, GenericContext context) {
    }

    private GenericResolver() {
    }

    static GenericContext context(Editor editor) {
        return withIndex(editor, parsed(editor.getDocument()));
    }

    @SuppressWarnings("try") // The timing scope logs when closed.
    static GenericContext parsed(Document document) {
        long stamp = document.getModificationStamp();
        var cached = document.getUserData(PARSED);
        if (cached != null && cached.stamp() == stamp) {
            return cached.context();
        }
        GenericContext parsed;
        try (@SuppressWarnings("unused") var timing = AnalysisTiming.start("generic parse", "")) {
            parsed = new GenericContext(document.getImmutableCharSequence());
        }
        if (document.getModificationStamp() == stamp) {
            document.putUserData(PARSED, new Parsed(stamp, parsed));
        }
        return parsed;
    }

    static GenericContext context(Editor editor, CharSequence text) {
        // Surround-selection uses speculative text, not the current document.
        return withIndex(editor, new GenericContext(text));
    }

    private static GenericContext withIndex(Editor editor, GenericContext parsed) {
        var project = editor.getProject();
        var imports = parsed.imports();
        if (project == null || imports.isEmpty() || DumbService.isDumb(project)) {
            return parsed;
        }
        Map<String, Boolean> resolved = new HashMap<>();
        return parsed.withExternalLookup((name, owner) -> resolved.computeIfAbsent(
                name + ":" + owner, ignored -> {
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
    }
}

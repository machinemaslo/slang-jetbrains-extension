package slanglsp.editor;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import slanglsp.language.SlangFileType;

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
                    var scope = GlobalSearchScope.getScopeRestrictedByFileTypes(
                            GlobalSearchScope.allScope(project), SlangFileType.INSTANCE);
                    boolean[] found = {false};
                    try {
                        var search = PsiSearchHelper.getInstance(project);
                        if (search.isCheapEnoughToSearch(name, scope, null)
                                == PsiSearchHelper.SearchCostResult.TOO_MANY_OCCURRENCES) {
                            return false;
                        }
                        search.processAllFilesWithWord(name, scope, candidate -> {
                            var document = PsiDocumentManager.getInstance(project).getDocument(candidate);
                            if (document == null || document == editor.getDocument()) {
                                return true;
                            }
                            var declarations = new GenericContext(document.getImmutableCharSequence());
                            String module = declarations.moduleName();
                            String filename = candidate.getVirtualFile().getNameWithoutExtension();
                            if (!imports.contains(module) && !imports.contains(filename)) {
                                return true;
                            }
                            found[0] = declarations.declaresGeneric(name, owner);
                            return !found[0];
                        }, true);
                    } catch (IndexNotReadyException indexing) {
                        return false;
                    }
                    return found[0];
                }));
        return contexts[0];
    }
}

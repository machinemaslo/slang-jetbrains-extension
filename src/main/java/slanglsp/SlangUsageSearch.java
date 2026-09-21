package slanglsp;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.util.ProgressIndicatorUtils;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.*;
import com.intellij.usageView.UsageInfo;
import com.intellij.util.Processor;
import com.redhat.devtools.lsp4ij.LSPIJUtils;
import com.redhat.devtools.lsp4ij.features.LSPPsiElementFactory;
import com.redhat.devtools.lsp4ij.features.navigation.LSPDefinitionParams;
import com.redhat.devtools.lsp4ij.features.navigation.LSPDefinitionSupport;
import com.redhat.devtools.lsp4ij.features.references.LSPReferenceParams;
import com.redhat.devtools.lsp4ij.features.references.LSPReferenceSupport;
import com.redhat.devtools.lsp4ij.usages.LocationData;
import org.eclipse.lsp4j.*;

import java.net.URI;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Indexed candidates, verified one request at a time; no network wait holds a read action. */
final class SlangUsageSearch {
    private record Source(PsiFile file, Document document, long stamp, int offset, String name) {
        void checkUnchanged() {
            ProgressManager.checkCanceled();
            if (document.getModificationStamp() != stamp) throw new ProcessCanceledException();
        }
    }

    private record Symbol(URI uri, int line, int character) {
        static Symbol from(Location location) {
            var start = location.getRange().getStart();
            return new Symbol(URI.create(location.getUri()).normalize(), start.getLine(), start.getCharacter());
        }
    }

    static boolean process(PsiElement element, SearchScope scope, Processor<? super UsageInfo> consumer) {
        Source source = SlangReadAction.compute(() -> {
            if (!element.isValid()) return null;
            PsiFile file = element.getContainingFile();
            Document document = LSPIJUtils.getDocument(file);
            if (document == null) return null;
            var leaf = file.findElementAt(element.getTextOffset());
            return leaf == null ? null : new Source(file, document, document.getModificationStamp(),
                    leaf.getTextOffset(), leaf.getText());
        });
        if (source == null) return true;
        Project project = source.file.getProject();
        Set<Symbol> declarations = new HashSet<>();
        var virtualFile = source.file.getVirtualFile();
        if (virtualFile instanceof SlangBuiltinFiles.BuiltinFile builtin) {
            Position position = SlangReadAction.compute(() -> LSPIJUtils.toPosition(source.offset, source.document));
            declarations.add(new Symbol(builtin.uri(), position.getLine(), position.getCharacter()));
        } else {
            List<LocationData> references = references(source);
            source.checkUnchanged();
            // LSP4IJ returns null only when no server supports references. Empty is a valid result.
            if (references != null) {
                Set<String> seen = new HashSet<>();
                for (var reference : references) {
                    ProgressManager.checkCanceled();
                    var target = LSPPsiElementFactory.toPsiElement(reference.location(),
                            reference.languageServer().getClientFeatures(), project);
                    if (target != null && seen.add(reference.location().toString())) {
                        UsageInfo usage = SlangReadAction.compute(() -> PsiSearchScopeUtil.isInScope(scope, target)
                                ? new UsageInfo(target) : null);
                        if (usage != null && !consumer.process(usage)) return false;
                    }
                }
                return true;
            }
            for (var location : definitions(source.file, source.document, source.offset)) {
                declarations.add(Symbol.from(location.location()));
            }
        }
        // slangd returns null for some declaration-site definition requests (including globals).
        // In that case use the source location; candidates still require semantic confirmation.
        if (declarations.isEmpty()) {
            Position position = SlangReadAction.compute(() -> LSPIJUtils.toPosition(source.offset, source.document));
            declarations.add(new Symbol(fileUri(virtualFile), position.getLine(), position.getCharacter()));
        }
        source.checkUnchanged();
        var files = candidateFiles(project, scope, source.name);
        int visited = 0;
        for (VirtualFile file : files) {
            source.checkUnchanged();
            var indicator = ProgressManager.getInstance().getProgressIndicator();
            if (indicator != null) {
                indicator.setText("Finding usages of " + source.name);
                indicator.setText2(file.getName());
                indicator.setFraction((double) visited++ / Math.max(1, files.size()));
            }
            Source candidate = SlangReadAction.compute(() -> {
                PsiFile psi = PsiManager.getInstance(project).findFile(file);
                Document doc = FileDocumentManager.getInstance().getDocument(file);
                return psi == null || doc == null ? null : new Source(psi, doc, doc.getModificationStamp(), 0, source.name);
            });
            if (candidate == null) continue;
            List<Integer> offsets = SlangReadAction.compute(() -> SlangUsageCandidates.find(candidate.document.getText(), source.name));
            for (int offset : offsets) {
                source.checkUnchanged();
                candidate.checkUnchanged();
                Position position = SlangReadAction.compute(() -> LSPIJUtils.toPosition(offset, candidate.document));
                // The declaration itself is not a usage.
                if (declarations.contains(new Symbol(fileUri(file),
                        position.getLine(), position.getCharacter()))) continue;
                boolean inScope = SlangReadAction.compute(() -> {
                    var leaf = candidate.file.findElementAt(offset);
                    return leaf != null && PsiSearchScopeUtil.isInScope(scope, leaf);
                });
                if (!inScope) continue;
                List<LocationData> targets = definitions(candidate.file, candidate.document, offset);
                source.checkUnchanged();
                candidate.checkUnchanged();
                if (targets.stream().anyMatch(target -> declarations.contains(Symbol.from(target.location())))) {
                    UsageInfo usage = SlangReadAction.compute(() -> new UsageInfo(candidate.file, offset, offset + source.name.length()));
                    if (!consumer.process(usage)) return false;
                }
            }
        }
        return true;
    }

    static URI fileUri(VirtualFile file) {
        if (file instanceof SlangBuiltinFiles.BuiltinFile builtin) return builtin.uri();
        URI uri = com.redhat.devtools.lsp4ij.client.features.FileUriSupport.getFileUri(file, null);
        if (uri == null) {
            // Abort the whole search/rename instead of silently skipping an unresolvable file.
            throw new IllegalStateException("Cannot resolve a language-server URI for " + file.getName());
        }
        return uri.normalize();
    }

    static Set<VirtualFile> candidateFiles(Project project, SearchScope scope, String name) {
        return SlangReadAction.compute(() -> {
            Set<VirtualFile> files = new LinkedHashSet<>();
            if (scope instanceof LocalSearchScope local) {
                for (PsiElement element : local.getScope()) {
                    if (element.getContainingFile() != null) files.add(element.getContainingFile().getVirtualFile());
                }
            } else {
                GlobalSearchScope global = scope instanceof GlobalSearchScope gs ? gs : GlobalSearchScope.projectScope(project);
                PsiSearchHelper.getInstance(project).processAllFilesWithWord(name,
                        GlobalSearchScope.getScopeRestrictedByFileTypes(global, SlangFileType.INSTANCE), psi -> {
                            files.add(psi.getVirtualFile());
                            return true;
                        }, true);
            }
            // A just-typed occurrence may not be in the word index yet.
            for (Document document : FileDocumentManager.getInstance().getUnsavedDocuments()) {
                VirtualFile file = FileDocumentManager.getInstance().getFile(document);
                if (file != null && scope.contains(file) && document.getText().contains(name)) files.add(file);
            }
            files.removeIf(file -> file == null || !file.isValid() || file instanceof SlangBuiltinFiles.BuiltinFile
                    || file.getFileType() != SlangFileType.INSTANCE);
            return files;
        });
    }

    private static List<LocationData> references(Source source) {
        var support = new LSPReferenceSupport(source.file);
        CompletableFuture<List<LocationData>> future = SlangReadAction.compute(() -> {
            var params = new LSPReferenceParams(new TextDocumentIdentifier(),
                    LSPIJUtils.toPosition(source.offset, source.document), source.offset);
            params.setContext(new ReferenceContext(false));
            return support.getReferences(params);
        });
        try {
            return ProgressIndicatorUtils.awaitWithCheckCanceled(future);
        } finally {
            if (!future.isDone()) support.cancel();
        }
    }

    static List<LocationData> definitions(PsiFile file, Document document, int offset) {
        var support = new LSPDefinitionSupport(file);
        CompletableFuture<List<LocationData>> future = SlangReadAction.compute(() -> support.getDefinitions(
                new LSPDefinitionParams(new TextDocumentIdentifier(), LSPIJUtils.toPosition(offset, document), offset)));
        try {
            List<LocationData> result = ProgressIndicatorUtils.awaitWithCheckCanceled(future);
            return result == null ? List.of() : result;
        } finally {
            if (!future.isDone()) support.cancel();
        }
    }
}

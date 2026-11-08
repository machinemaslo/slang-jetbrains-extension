package slanglsp.editor;

import slanglsp.SlangBundle;
import slanglsp.language.SlangFileType;
import slanglsp.server.BuiltinFiles;

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

/** Indexed candidates, verified with bounded concurrent requests; no network wait holds a read action. */
final class UsageSearch {
    private record Source(PsiFile file, Document document, long stamp, int offset, String name) {
        void checkUnchanged() {
            ProgressManager.checkCanceled();

            if (document.getModificationStamp() != stamp)
                throw new ProcessCanceledException();
        }
    }

    private record Symbol(URI uri, int line, int character) {
        static Symbol from(Location location) {
            var start = location.getRange().getStart();

            return new Symbol(URI.create(location.getUri()).normalize(), start.getLine(), start.getCharacter());
        }
    }

    static boolean process(PsiElement element, SearchScope scope, Processor<? super UsageInfo> consumer) {
        Source source = snapshot(element);

        if (source == null) {
            return true;
        }

        Project project = source.file.getProject();
        Set<Symbol> declarations = new HashSet<>();
        var virtualFile = source.file.getVirtualFile();

        if (virtualFile instanceof BuiltinFiles.BuiltinFile builtin) {
            Position position = ReadActions.compute(() -> LSPIJUtils.toPosition(source.offset, source.document));
            declarations.add(new Symbol(builtin.uri(), position.getLine(), position.getCharacter()));
        } else {
            List<LocationData> references = references(source);
            source.checkUnchanged();
            // LSP4IJ returns null only when no server supports references. Empty is a valid result.

            if (references != null) {
                return processReferences(project, references, scope, consumer);
            }

            for (var location : definitions(source.file, source.document, source.offset)) {
                declarations.add(Symbol.from(location.location()));
            }
        }
        // slangd returns null for some declaration-site definition requests (including globals).
        // In that case use the source location; candidates still require semantic confirmation.

        if (declarations.isEmpty()) {
            Position position = ReadActions.compute(() -> LSPIJUtils.toPosition(source.offset, source.document));
            declarations.add(new Symbol(fileUri(virtualFile), position.getLine(), position.getCharacter()));
        }

        source.checkUnchanged();

        return processCandidates(source, declarations, scope, consumer);
    }

    /** Absence must be proven: unresolved candidates are not evidence of an unused symbol. */
    static boolean isUnused(PsiElement element) {
        Source source = snapshot(element);

        if (source == null) {
            return false;
        }

        var references = references(source);
        source.checkUnchanged();

        if (references != null) {
            return references.isEmpty();
        }

        var declaration = ReadActions.compute(() -> {
            var position = LSPIJUtils.toPosition(source.offset, source.document);

            return new Symbol(fileUri(source.file.getVirtualFile()), position.getLine(), position.getCharacter());
        });
        var scope = GlobalSearchScope.projectScope(source.file.getProject());

        for (var file : candidateFiles(source.file.getProject(), scope, source.name)) {
            Source candidate = ReadActions.compute(() -> {
                var psi = PsiManager.getInstance(source.file.getProject()).findFile(file);
                var doc = FileDocumentManager.getInstance().getDocument(file);

                return psi == null || doc == null ? null
                                                  : new Source(psi, doc, doc.getModificationStamp(), 0, source.name);
            });

            if (candidate == null) {
                return false;
            }

            var offsets = ReadActions.compute(
                    ()
                            -> UsageCandidates.find(candidate.document.getText(), source.name)
                                    .stream()
                                    .filter(offset
                                            -> !file.equals(source.file.getVirtualFile()) || offset != source.offset)
                                    .toList());

            if (!processDefinitions(candidate.file, candidate.document, offsets,
                        ()
                                -> {
                            source.checkUnchanged();
                            candidate.checkUnchanged();
                        },
                        (offset, targets)
                                -> !targets.isEmpty() &&
                                           targets.stream().noneMatch(
                                                   target -> declaration.equals(Symbol.from(target.location()))))) {
                return false;
            }
        }

        source.checkUnchanged();

        return true;
    }

    private static Source snapshot(PsiElement element) {
        return ReadActions.compute(() -> {
            if (!element.isValid()) {
                return null;
            }

            PsiFile file = element.getContainingFile();
            Document document = LSPIJUtils.getDocument(file);

            if (document == null) {
                return null;
            }

            var leaf = file.findElementAt(element.getTextOffset());

            return leaf == null
                    ? null
                    : new Source(file, document, document.getModificationStamp(), leaf.getTextOffset(), leaf.getText());
        });
    }

    private static boolean processReferences(
            Project project, List<LocationData> references, SearchScope scope, Processor<? super UsageInfo> consumer) {
        Set<String> seen = new HashSet<>();

        for (var reference : references) {
            ProgressManager.checkCanceled();
            // LSP4IJ's documented PSI conversion requires its experimental client features.
            var target = LSPPsiElementFactory.toPsiElement(
                    reference.location(), reference.languageServer().getClientFeatures(), project);

            if (target != null && seen.add(reference.location().toString())) {
                UsageInfo usage = ReadActions.compute(
                        () -> PsiSearchScopeUtil.isInScope(scope, target) ? new UsageInfo(target) : null);

                if (usage != null && !consumer.process(usage))
                    return false;
            }
        }

        return true;
    }

    private static boolean processCandidates(
            Source source, Set<Symbol> declarations, SearchScope scope, Processor<? super UsageInfo> consumer) {
        Project project = source.file.getProject();
        var files = candidateFiles(project, scope, source.name);
        int visited = 0;

        for (VirtualFile file : files) {
            source.checkUnchanged();
            var indicator = ProgressManager.getInstance().getProgressIndicator();

            if (indicator != null) {
                indicator.setText(SlangBundle.message("usages.progress", source.name));
                indicator.setText2(file.getName());
                indicator.setFraction((double)visited++ / Math.max(1, files.size()));
            }

            Source candidate = ReadActions.compute(() -> {
                PsiFile psi = PsiManager.getInstance(project).findFile(file);
                Document doc = FileDocumentManager.getInstance().getDocument(file);

                return psi == null || doc == null ? null
                                                  : new Source(psi, doc, doc.getModificationStamp(), 0, source.name);
            });

            if (candidate == null) {
                continue;
            }

            List<Integer> offsets =
                    ReadActions.compute(() -> UsageCandidates.find(candidate.document.getText(), source.name));
            List<Integer> inScopeOffsets = ReadActions.compute(
                    ()
                            -> offsets.stream()
                                    .filter(offset -> {
                                        Position position = LSPIJUtils.toPosition(offset, candidate.document);

                                        if (declarations.contains(new Symbol(
                                                    fileUri(file), position.getLine(), position.getCharacter())))
                                            return false;
                                        var leaf = candidate.file.findElementAt(offset);

                                        return leaf != null && PsiSearchScopeUtil.isInScope(scope, leaf);
                                    })
                                    .toList());

            if (!processDefinitions(candidate.file, candidate.document, inScopeOffsets,
                        ()
                                -> {
                            source.checkUnchanged();
                            candidate.checkUnchanged();
                        },
                        (offset, targets) -> {
                            if (targets.stream().anyMatch(
                                        target -> declarations.contains(Symbol.from(target.location())))) {
                                UsageInfo usage = ReadActions.compute(
                                        () -> new UsageInfo(candidate.file, offset, offset + source.name.length()));

                                return consumer.process(usage);
                            }

                            return true;
                        }))
                return false;
        }

        return true;
    }

    static URI fileUri(VirtualFile file) {
        if (file instanceof BuiltinFiles.BuiltinFile builtin)
            return builtin.uri();
        URI uri = com.redhat.devtools.lsp4ij.client.features.FileUriSupport.getFileUri(file, null);

        if (uri == null) {
            // Abort the whole search/rename instead of silently skipping an unresolvable file.
            throw new IllegalStateException(SlangBundle.message("usages.uri.failed", file.getName()));
        }

        return uri.normalize();
    }

    static Set<VirtualFile> candidateFiles(Project project, SearchScope scope, String name) {
        return ReadActions.compute(() -> {
            Set<VirtualFile> files = new LinkedHashSet<>();

            if (scope instanceof LocalSearchScope local) {
                for (PsiElement element : local.getScope()) {
                    if (element.getContainingFile() != null)
                        files.add(element.getContainingFile().getVirtualFile());
                }
            } else {
                GlobalSearchScope global =
                        scope instanceof GlobalSearchScope gs ? gs : GlobalSearchScope.projectScope(project);
                PsiSearchHelper.getInstance(project).processAllFilesWithWord(
                        name, GlobalSearchScope.getScopeRestrictedByFileTypes(global, SlangFileType.INSTANCE), psi -> {
                            files.add(psi.getVirtualFile());

                            return true;
                        }, true);
            }
            // A just-typed occurrence may not be in the word index yet.

            for (Document document : FileDocumentManager.getInstance().getUnsavedDocuments()) {
                VirtualFile file = FileDocumentManager.getInstance().getFile(document);

                if (file != null && scope.contains(file) && document.getText().contains(name))
                    files.add(file);
            }

            files.removeIf(file
                    -> file == null || !file.isValid() || file instanceof BuiltinFiles.BuiltinFile ||
                               file.getFileType() != SlangFileType.INSTANCE);

            return files;
        });
    }

    private static List<LocationData> references(Source source) {
        var support = new LSPReferenceSupport(source.file);
        CompletableFuture<List<LocationData>> future = ReadActions.compute(() -> {
            var params = new LSPReferenceParams(
                    new TextDocumentIdentifier(), LSPIJUtils.toPosition(source.offset, source.document), source.offset);
            params.setContext(new ReferenceContext(false));

            return support.getReferences(params);
        });

        try {
            return ProgressIndicatorUtils.awaitWithCheckCanceled(future);
        } finally {
            if (!future.isDone())
                support.cancel();
        }
    }

    private record DefinitionRequest(LSPDefinitionSupport support, CompletableFuture<List<LocationData>> future)
            implements AutoCloseable {
        @Override
        public void close() {
            if (!future.isDone())
                support.cancel();
        }

        List<LocationData> await() {
            var result = ProgressIndicatorUtils.awaitWithCheckCanceled(future);

            return result == null ? List.of() : result;
        }
    }

    private static DefinitionRequest requestDefinition(PsiFile file, Document document, int offset) {
        var support = new LSPDefinitionSupport(file);

        try {
            var future = ReadActions.compute(
                    ()
                            -> support.getDefinitions(new LSPDefinitionParams(
                                    new TextDocumentIdentifier(), LSPIJUtils.toPosition(offset, document), offset)));

            return new DefinitionRequest(support, future);
        } catch (RuntimeException failure) {
            support.cancel();
            throw failure;
        }
    }

    static List<LocationData> definitions(PsiFile file, Document document, int offset) {
        try (var request = requestDefinition(file, document, offset)) {
            return request.await();
        }
    }

    // Keep a small window in flight, preserving result order and cancelling unused requests.
    static boolean processDefinitions(PsiFile file, Document document, List<Integer> offsets, Runnable check,
            java.util.function.BiPredicate<Integer, List<LocationData>> consumer) {
        for (int start = 0; start < offsets.size(); start += 8) {
            List<DefinitionRequest> requests = new ArrayList<>();

            try {
                int end = Math.min(start + 8, offsets.size());

                for (int i = start; i < end; i++) {
                    ProgressManager.checkCanceled();
                    ReadActions.run(check);
                    requests.add(requestDefinition(file, document, offsets.get(i)));
                }

                for (int i = 0; i < requests.size(); i++) {
                    var targets = requests.get(i).await();
                    ReadActions.run(check);

                    if (!consumer.test(offsets.get(start + i), targets))
                        return false;
                }
            } finally {
                requests.forEach(DefinitionRequest::close);
            }
        }

        return true;
    }
}

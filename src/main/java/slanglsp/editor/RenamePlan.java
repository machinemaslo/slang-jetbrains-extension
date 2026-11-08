package slanglsp.editor;

import slanglsp.SlangBundle;
import slanglsp.language.SlangFileType;
import slanglsp.server.BuiltinFiles;

import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.usageView.UsageInfo;
import com.redhat.devtools.lsp4ij.LSPIJUtils;
import org.eclipse.lsp4j.Location;

import java.net.URI;
import java.util.*;

/** A project-local rename verified by definitions, with an immutable document snapshot. */
final class RenamePlan {
    private record Symbol(URI uri, int line, int character) {
        static Symbol of(Location location) {
            var position = location.getRange().getStart();

            return new Symbol(URI.create(location.getUri()).normalize(), position.getLine(), position.getCharacter());
        }
    }

    private record Source(PsiFile file, Document document, long stamp, String text, URI uri) {
        Symbol symbol(int offset) {
            var position = LSPIJUtils.toPosition(offset, document);

            return new Symbol(uri, position.getLine(), position.getCharacter());
        }

        void check() {
            if (!file.isValid() || !file.getVirtualFile().isValid() || document.getModificationStamp() != stamp ||
                    !uri.equals(UsageSearch.fileUri(file.getVirtualFile()))) {
                throw new IllegalStateException(
                        SlangBundle.message("rename.files.changed.during.rename.run.rename.again"));
            }
        }
    }

    private record Edit(Source source, int offset) {}

    private final Project project;
    final String oldName;
    final String newName;
    private final List<Source> sources;
    private final List<Edit> edits;

    private RenamePlan(Project project, String oldName, String newName, List<Source> sources, List<Edit> edits) {
        this.project = project;
        this.oldName = oldName;
        this.newName = newName;
        this.sources = List.copyOf(sources);
        this.edits = List.copyOf(edits);
    }

    static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z_][A-Za-z0-9_]*") &&
                UsageCandidates.find(name, name).equals(List.of(0));
    }

    private static Set<VirtualFile> projectFiles(Project project) {
        var scope = GlobalSearchScope.projectScope(project);
        Set<VirtualFile> files = new LinkedHashSet<>(FileTypeIndex.getFiles(SlangFileType.INSTANCE, scope));

        for (Document document : FileDocumentManager.getInstance().getUnsavedDocuments()) {
            VirtualFile file = FileDocumentManager.getInstance().getFile(document);

            if (file != null && scope.contains(file) && file.getFileType() == SlangFileType.INSTANCE)
                files.add(file);
        }

        files.removeIf(file -> file instanceof BuiltinFiles.BuiltinFile);

        return files;
    }

    static RenamePlan collect(PsiElement element, String newName) {
        if (!validName(newName)) {
            throw invalidRename("rename.enter.a.slang.identifier.not.a.keyword.or.built.in.type");
        }

        Project project = element.getProject();
        String name = ReadActions.compute(element::getText);

        if (!validName(name) || name.equals(newName)) {
            throw invalidRename("rename.choose.a.different.identifier");
        }

        List<Source> sources = snapshotSources(project, name);
        Declaration declaration = resolveDeclaration(element, sources, name);
        List<Edit> edits = collectEdits(sources, declaration, name);
        RenamePlan plan = new RenamePlan(project, name, newName, sources, edits);

        ReadActions.run(plan::validate);

        return plan;
    }

    private static List<Source> snapshotSources(Project project, String name) {
        var scope = GlobalSearchScope.projectScope(project);
        Set<VirtualFile> candidates = UsageSearch.candidateFiles(project, scope, name);

        return ReadActions.compute(() -> {
            List<Source> result = new ArrayList<>();

            for (VirtualFile file : projectFiles(project)) {
                ProgressManager.checkCanceled();
                var psi = PsiManager.getInstance(project).findFile(file);
                var document = FileDocumentManager.getInstance().getDocument(file);

                if (psi == null || document == null) {
                    throw new IllegalStateException(SlangBundle.message("rename.cannot.read", file.getName()));
                }

                String text = candidates.contains(file) ? document.getText() : "";
                result.add(new Source(psi, document, document.getModificationStamp(), text, UsageSearch.fileUri(file)));
            }

            return result;
        });
    }

    private record Declaration(Source source, Symbol symbol, int offset, boolean confirmed) {}

    private static Declaration resolveDeclaration(PsiElement element, List<Source> sources, String name) {
        Source origin = ReadActions.compute(
                ()
                        -> sources.stream()
                                .filter(source -> source.file == element.getContainingFile())
                                .findFirst()
                                .orElseThrow(
                                        ()
                                                -> invalidRename(
                                                        "rename.only.symbols.declared.in.this.project.s.slang.files.can.be.renamed")));

        int originOffset = ReadActions.compute(element::getTextOffset);
        Set<Symbol> targets = definitions(origin, originOffset);

        if (targets.size() > 1) {
            throw invalidRename("rename.the.symbol.has.multiple.definitions.and.cannot.be.renamed.safely");
        }

        Symbol symbol =
                targets.isEmpty() ? ReadActions.compute(() -> origin.symbol(originOffset)) : targets.iterator().next();
        Source source =
                sources.stream()
                        .filter(candidate -> candidate.uri.equals(symbol.uri))
                        .findFirst()
                        .orElseThrow(
                                () -> invalidRename("rename.built.in.and.external.declarations.cannot.be.renamed"));

        int offset = declarationOffset(source, symbol, name);

        if (offset < 0) {
            throw invalidRename("rename.slangd.did.not.identify.a.matching.declaration");
        }

        return new Declaration(source, symbol, offset, !targets.isEmpty());
    }

    private static int declarationOffset(Source source, Symbol symbol, String name) {
        return ReadActions.compute(() -> {
            source.check();

            if (symbol.line < 0 || symbol.line >= source.document.getLineCount()) {
                return -1;
            }

            int offset = source.document.getLineStartOffset(symbol.line) + symbol.character;

            return UsageCandidates.find(source.text, name).contains(offset) ? offset : -1;
        });
    }

    private static List<Edit> collectEdits(List<Source> sources, Declaration declaration, String name) {
        List<Edit> edits = new ArrayList<>();
        edits.add(new Edit(declaration.source, declaration.offset));
        boolean[] confirmed = {declaration.confirmed};

        for (Source source : sources) {
            var indicator = ProgressManager.getInstance().getProgressIndicator();

            if (indicator != null) {
                indicator.setText2(source.file.getName());
            }

            List<Integer> offsets =
                    UsageCandidates.find(source.text, name)
                            .stream()
                            .filter(offset -> source != declaration.source || offset != declaration.offset)
                            .toList();

            UsageSearch.processDefinitions(
                    source.file, source.document, offsets, source::check, (offset, locations) -> {
                        Set<Symbol> targets = new HashSet<>();
                        locations.forEach(location -> targets.add(Symbol.of(location.location())));

                        if (targets.contains(declaration.symbol)) {
                            if (targets.size() != 1) {
                                throw invalidRename("rename.an.ambiguous.usage.prevents.a.safe.rename");
                            }

                            edits.add(new Edit(source, offset));
                            confirmed[0] = true;
                        }

                        return true;
                    });
        }

        if (!confirmed[0]) {
            throw invalidRename(
                    "rename.slangd.could.not.verify.this.declaration.rename.is.unavailable.for.this.symbol");
        }

        return edits;
    }

    private static IllegalArgumentException invalidRename(String messageKey) {
        return new IllegalArgumentException(SlangBundle.message(messageKey));
    }

    private static Set<Symbol> definitions(Source source, int offset) {
        ReadActions.run(source::check);
        var locations = UsageSearch.definitions(source.file, source.document, offset);
        ReadActions.run(source::check);
        Set<Symbol> result = new HashSet<>();
        locations.forEach(location -> result.add(Symbol.of(location.location())));

        return result;
    }

    UsageInfo[] usages() {
        return ReadActions.compute(() -> {
            validate();

            return edits.stream()
                    .map(edit -> new UsageInfo(edit.source.file, edit.offset, edit.offset + oldName.length()))
                    .toArray(UsageInfo[] ::new);
        });
    }

    private void validate() {
        Set<VirtualFile> original = new HashSet<>();

        for (Source source : sources) {
            source.check();
            original.add(source.file.getVirtualFile());
        }

        if (!original.equals(projectFiles(project)))
            throw new IllegalStateException(SlangBundle.message("rename.project.files.changed.run.rename.again"));

        for (Edit edit : edits) {
            if (!edit.source.file.isWritable() || !edit.source.document.isWritable()) {
                throw new IllegalStateException(SlangBundle.message("rename.read.only", edit.source.file.getName()));
            }

            if (!edit.source.document.getText().regionMatches(edit.offset, oldName, 0, oldName.length())) {
                throw new IllegalStateException(SlangBundle.message("rename.a.usage.changed.run.rename.again"));
            }
        }
    }

    void apply() {
        WriteCommandAction.runWriteCommandAction(
                project, SlangBundle.message("rename.command", oldName, newName), null, () -> {
                    // Validate every file before the first edit; never apply a partial stale plan.
                    validate();
                    CommandProcessor.getInstance().markCurrentCommandAsGlobal(project);
                    edits.stream()
                            .sorted(Comparator.comparingInt(Edit::offset).reversed())
                            .forEach(edit
                                    -> edit.source.document.replaceString(
                                            edit.offset, edit.offset + oldName.length(), newName));
                    PsiDocumentManager.getInstance(project).commitAllDocuments();
                });
    }
}

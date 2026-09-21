package slanglsp;

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
final class SlangRenamePlan {
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
            if (!file.isValid() || !file.getVirtualFile().isValid() || document.getModificationStamp() != stamp
                    || !uri.equals(SlangUsageSearch.fileUri(file.getVirtualFile()))) {
                throw new IllegalStateException("Files changed during rename. Run Rename again.");
            }
        }
    }
    private record Edit(Source source, int offset) { }

    private final Project project;
    final String oldName;
    final String newName;
    private final List<Source> sources;
    private final List<Edit> edits;

    private SlangRenamePlan(Project project, String oldName, String newName, List<Source> sources, List<Edit> edits) {
        this.project = project;
        this.oldName = oldName;
        this.newName = newName;
        this.sources = List.copyOf(sources);
        this.edits = List.copyOf(edits);
    }

    static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z_][A-Za-z0-9_]*")
                && SlangUsageCandidates.find(name, name).equals(List.of(0));
    }

    private static Set<VirtualFile> projectFiles(Project project) {
        var scope = GlobalSearchScope.projectScope(project);
        Set<VirtualFile> files = new LinkedHashSet<>(FileTypeIndex.getFiles(SlangFileType.INSTANCE, scope));
        for (Document document : FileDocumentManager.getInstance().getUnsavedDocuments()) {
            VirtualFile file = FileDocumentManager.getInstance().getFile(document);
            if (file != null && scope.contains(file) && file.getFileType() == SlangFileType.INSTANCE) files.add(file);
        }
        files.removeIf(file -> file instanceof SlangBuiltinFiles.BuiltinFile);
        return files;
    }

    static SlangRenamePlan collect(PsiElement element, String newName) {
        if (!validName(newName)) throw new IllegalArgumentException("Enter a Slang identifier, not a keyword or built-in type.");
        Project project = element.getProject();
        String name = SlangReadAction.compute(element::getText);
        if (!validName(name) || name.equals(newName)) throw new IllegalArgumentException("Choose a different identifier.");
        List<Source> sources = SlangReadAction.compute(() -> {
            List<Source> result = new ArrayList<>();
            for (VirtualFile file : projectFiles(project)) {
                ProgressManager.checkCanceled();
                var psi = PsiManager.getInstance(project).findFile(file);
                var doc = FileDocumentManager.getInstance().getDocument(file);
                if (psi == null || doc == null) throw new IllegalStateException("Cannot read " + file.getName());
                result.add(new Source(psi, doc, doc.getModificationStamp(), doc.getText(), SlangUsageSearch.fileUri(file)));
            }
            return result;
        });
        Source origin = SlangReadAction.compute(() -> sources.stream()
                .filter(source -> source.file == element.getContainingFile()).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Only symbols declared in this project's Slang files can be renamed.")));
        int originOffset = SlangReadAction.compute(element::getTextOffset);
        var definitions = definitions(origin, originOffset);
        if (definitions.size() > 1) throw new IllegalArgumentException("The symbol has multiple definitions and cannot be renamed safely.");
        Symbol symbol = definitions.isEmpty() ? SlangReadAction.compute(() -> origin.symbol(originOffset)) : definitions.iterator().next();
        Source declaration = sources.stream().filter(source -> source.uri.equals(symbol.uri))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Built-in and external declarations cannot be renamed."));
        int declarationOffset = SlangReadAction.compute(() -> {
            declaration.check();
            if (symbol.line < 0 || symbol.line >= declaration.document.getLineCount()) return -1;
            int offset = declaration.document.getLineStartOffset(symbol.line) + symbol.character;
            return SlangUsageCandidates.find(declaration.text, name).contains(offset) ? offset : -1;
        });
        if (declarationOffset < 0) throw new IllegalArgumentException("slangd did not identify a matching declaration.");
        List<Edit> edits = new ArrayList<>();
        edits.add(new Edit(declaration, declarationOffset));
        boolean confirmed = !definitions.isEmpty();
        for (Source source : sources) {
            var indicator = ProgressManager.getInstance().getProgressIndicator();
            if (indicator != null) indicator.setText2(source.file.getName());
            for (int offset : SlangUsageCandidates.find(source.text, name)) {
                ProgressManager.checkCanceled();
                if (source == declaration && offset == declarationOffset) continue;
                Set<Symbol> targets = definitions(source, offset);
                if (targets.contains(symbol)) {
                    if (targets.size() != 1) throw new IllegalArgumentException("An ambiguous usage prevents a safe rename.");
                    edits.add(new Edit(source, offset));
                    confirmed = true;
                }
            }
        }
        if (!confirmed) throw new IllegalArgumentException("slangd could not verify this declaration. Rename is unavailable for this symbol.");
        SlangRenamePlan plan = new SlangRenamePlan(project, name, newName, sources, edits);
        SlangReadAction.run(plan::validate);
        return plan;
    }

    private static Set<Symbol> definitions(Source source, int offset) {
        SlangReadAction.run(source::check);
        var locations = SlangUsageSearch.definitions(source.file, source.document, offset);
        SlangReadAction.run(source::check);
        Set<Symbol> result = new HashSet<>();
        locations.forEach(location -> result.add(Symbol.of(location.location())));
        return result;
    }

    UsageInfo[] usages() {
        return SlangReadAction.compute(() -> {
            validate();
            return edits.stream().map(edit -> new UsageInfo(edit.source.file, edit.offset, edit.offset + oldName.length()))
                    .toArray(UsageInfo[]::new);
        });
    }

    private void validate() {
        Set<VirtualFile> original = new HashSet<>();
        for (Source source : sources) {
            source.check();
            original.add(source.file.getVirtualFile());
        }
        if (!original.equals(projectFiles(project))) throw new IllegalStateException("Project files changed. Run Rename again.");
        for (Edit edit : edits) {
            if (!edit.source.file.isWritable() || !edit.source.document.isWritable()) {
                throw new IllegalStateException("Cannot rename: " + edit.source.file.getName() + " is read-only.");
            }
            if (!edit.source.document.getText().regionMatches(edit.offset, oldName, 0, oldName.length())) {
                throw new IllegalStateException("A usage changed. Run Rename again.");
            }
        }
    }

    void apply() {
        WriteCommandAction.runWriteCommandAction(project, "Rename " + oldName + " to " + newName, null, () -> {
            // Validate every file before the first edit; never apply a partial stale plan.
            validate();
            CommandProcessor.getInstance().markCurrentCommandAsGlobal(project);
            edits.stream().sorted(Comparator.comparingInt(Edit::offset).reversed()).forEach(edit ->
                    edit.source.document.replaceString(edit.offset, edit.offset + oldName.length(), newName));
            PsiDocumentManager.getInstance(project).commitAllDocuments();
        });
    }
}

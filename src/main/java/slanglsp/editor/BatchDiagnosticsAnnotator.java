package slanglsp.editor;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.ExternalAnnotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import slanglsp.SlangBundle;
import slanglsp.settings.ProjectSettings;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

/** Batch-only: editor diagnostics continue to be supplied by LSP4IJ. */
public final class BatchDiagnosticsAnnotator
        extends ExternalAnnotator<BatchDiagnosticsAnnotator.Input, BatchDiagnosticsAnnotator.Result> {
    private static final com.intellij.openapi.util.Key<com.intellij.psi.util.CachedValue<List<String>>> WORKSPACE_PATHS =
            com.intellij.openapi.util.Key.create("slang.batch.workspace.paths");
    record Input(PsiFile file, Document document, long stamp, String text, Path path,
                 ProjectSettings.State settings, List<String> searchPaths) {}
    record Result(Input input, List<BatchDiagnostics.Problem> problems, String failure) {}

    @Override
    public String getPairedBatchInspectionShortName() {
        return BatchDiagnosticsInspection.ID;
    }

    @Override
    public Input collectInformation(@NotNull PsiFile file, @NotNull Editor editor, boolean hasErrors) {
        return null; // Never spawn a compiler from editor highlighting.
    }

    @Override
    public Input collectInformation(@NotNull PsiFile file) {
        try (var timing = AnalysisTiming.start("batch snapshot and search paths", file.getName())) {
            return collect(file);
        }
    }

    private Input collect(PsiFile file) {
        var virtualFile = file.getVirtualFile();
        if (virtualFile == null || !virtualFile.isInLocalFileSystem()) return null;
        Document document = PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
        if (document == null) return null;
        var settings = ProjectSettings.getInstance(file.getProject()).getState();
        var paths = new LinkedHashSet<String>();
        Path source = virtualFile.toNioPath();
        paths.add(source.getParent().toString());
        String base = file.getProject().getBasePath();
        for (String path : settings.additionalIncludePaths) {
            if (!path.isBlank()) paths.add(path);
        }
        if (base != null) paths.add(base);
        if (Boolean.TRUE.equals(settings.enableSearchingSubDirectoriesOfWorkspace)) {
            paths.addAll(workspacePaths(file.getProject()));
        }
        return new Input(file, document, document.getModificationStamp(), document.getText(),
                source, settings, List.copyOf(paths));
    }

    private static List<String> workspacePaths(com.intellij.openapi.project.Project project) {
        return com.intellij.psi.util.CachedValuesManager.getManager(project).getCachedValue(
                project, WORKSPACE_PATHS, () -> {
                    var directories = new LinkedHashSet<String>();
                    for (String extension : List.of("slang", "slangh")) {
                        for (var candidate : FilenameIndex.getAllFilesByExt(
                                project, extension, GlobalSearchScope.projectScope(project))) {
                            if (candidate.isInLocalFileSystem()) {
                                directories.add(candidate.getParent().getPath());
                            }
                        }
                    }
                    return com.intellij.psi.util.CachedValueProvider.Result.create(List.copyOf(directories),
                            com.intellij.openapi.vfs.VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                            com.intellij.openapi.roots.ProjectRootModificationTracker.getInstance(project));
                }, false);
    }

    @Override
    public Result doAnnotate(Input input) {
        try (var timing = AnalysisTiming.start("batch diagnostics total", input.path().toString())) {
            return new Result(input, BatchDiagnostics.check(input), null);
        } catch (ProcessCanceledException cancelled) {
            throw cancelled;
        } catch (Exception failure) {
            return new Result(input, List.of(),
                    SlangBundle.message("inspection.batch.failed",
                            failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
        }
    }

    @Override
    public void apply(@NotNull PsiFile file, Result result, @NotNull AnnotationHolder holder) {
        if (result == null) return;
        if (result.input().document().getModificationStamp() != result.input().stamp()) {
            holder.newAnnotation(HighlightSeverity.ERROR, SlangBundle.message("inspection.batch.changed"))
                    .fileLevel().create();
            return;
        }
        if (result.failure() != null) {
            holder.newAnnotation(HighlightSeverity.ERROR, result.failure()).fileLevel().create();
            return;
        }
        for (var problem : result.problems()) {
            if (!problem.file().equals("<stdin>")) {
                // Included/imported-file failures must not be mistaken for a clean source file.
                holder.newAnnotation(problem.severity(), problem.file() + ":" + problem.line() + ": " + problem.message())
                        .fileLevel().create();
                continue;
            }
            int start = offset(result.input().text(), problem.line(), problem.column());
            int end = offset(result.input().text(), problem.endLine(), problem.endColumn());
            if (end <= start && start < file.getTextLength()) end = start + 1;
            var annotation = holder.newAnnotation(problem.severity(), problem.message())
                    .range(new TextRange(start, Math.max(start, end)));
            if (start == end) annotation.afterEndOfLine();
            annotation.create();
        }
    }

    /** Compiler positions are one-based Unicode code points; IDE offsets are UTF-16. */
    static int offset(String text, int line, int column) {
        int start = 0;
        for (int i = 1; i < line; i++) {
            int newline = text.indexOf('\n', start);
            if (newline < 0) return text.length();
            start = newline + 1;
        }
        int end = text.indexOf('\n', start);
        if (end < 0) end = text.length();
        return text.offsetByCodePoints(start, Math.min(Math.max(0, column - 1), text.codePointCount(start, end)));
    }
}

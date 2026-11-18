package slanglsp.editor;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.GlobalSearchScope;
import slanglsp.language.SlangFileType;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;

/** Read-only compiler and semantic operations for agent integrations. */
public final class AgentAnalysis {
    private static final int MAX_FILES = 16;
    private static final int MAX_DIAGNOSTICS = 30;
    private static final int MAX_REFERENCES = 20;

    private AgentAnalysis() {
    }

    public static String checkFiles(Project project, List<String> files) throws IOException {
        if (files.isEmpty() || files.size() > MAX_FILES) {
            throw new IllegalArgumentException("Supply 1–16 project-relative Slang files.");
        }
        Path root = root(project);
        var paths = new LinkedHashSet<Path>();
        for (String file : files) {
            paths.add(resolve(root, file));
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        var output = new StringBuilder();
        var diagnostics = new LinkedHashSet<String>();
        for (Path path : paths) {
            ProgressManager.checkCanceled();
            String relative = root.relativize(path).toString();
            var virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
            var input = ReadActions.compute(() -> {
                var file = virtualFile == null ? null : PsiManager.getInstance(project).findFile(virtualFile);
                return file == null || file.getFileType() != SlangFileType.INSTANCE ? null
                        : new BatchDiagnosticsAnnotator().collectInformation(file);
            });
            if (input == null) {
                output.append(relative).append(": not checked (not a Slang file)\n");
                continue;
            }
            long remaining = (deadline - System.nanoTime()) / 1_000_000;
            if (remaining <= 0) {
                output.append("Time limit reached; remaining files were not checked.\n");
                break;
            }
            List<BatchDiagnosticsAnnotator.Problem> problems;
            try {
                problems = BatchDiagnostics.check(input, (int) remaining);
            } catch (com.intellij.openapi.progress.ProcessCanceledException cancelled) {
                throw cancelled;
            } catch (Exception failure) {
                output.append(relative).append(": not checked (").append(clip(failure.getMessage(), 300)).append(")\n");
                continue;
            }
            if (input.document().getModificationStamp() != input.stamp()) {
                output.append(relative).append(": changed during check; rerun\n");
                continue;
            }
            if (problems.isEmpty()) {
                output.append(relative).append(": clean\n");
            }
            for (var problem : problems) {
                String diagnosticPath = "<stdin>".equals(problem.file()) ? relative : problem.file();
                diagnostics.add(diagnosticPath + ":" + problem.line() + ":" + problem.column() + ": "
                        + problem.severity() + ": " + clip(problem.message(), 350));
            }
        }
        diagnostics.stream().limit(MAX_DIAGNOSTICS).forEach(problem -> output.append(problem).append('\n'));
        if (diagnostics.size() > MAX_DIAGNOSTICS) {
            output.append("Additional diagnostics omitted; check individual files for more.\n");
        }
        return output.toString().stripTrailing();
    }

    public static String symbol(Project project, String path, int line, int column, boolean references) throws IOException {
        Path root = root(project);
        var virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(resolve(root, path));
        var source = ReadActions.compute(() -> {
            var file = virtualFile == null ? null : PsiManager.getInstance(project).findFile(virtualFile);
            var document = file == null ? null : PsiDocumentManager.getInstance(project).getDocument(file);
            if (file == null || file.getFileType() != SlangFileType.INSTANCE || document == null
                    || line < 1 || line > document.getLineCount() || column < 1) {
                throw new IllegalArgumentException("Expected a Slang file and a valid 1-based line and column.");
            }
            int start = document.getLineStartOffset(line - 1);
            if (column - 1 >= document.getLineEndOffset(line - 1) - start) {
                throw new IllegalArgumentException("Column is outside the source line.");
            }
            return new Source(file, document, document.getModificationStamp(), start + column - 1);
        });

        var output = new StringBuilder();
        try (@SuppressWarnings("unused") var deadline = AnalysisDeadline.open()) {
            var definitions = UsageSearch.definitions(source.file(), source.document(), source.offset());
            if (definitions.isEmpty()) {
                output.append("No Slang definition resolved at this position.\n");
            }
            definitions.stream().distinct().limit(6).forEach(definition -> {
                var location = definition.location();
                var start = location.getRange().getStart();
                output.append("Definition: ").append(display(root, location.getUri()))
                        .append(':').append(start.getLine() + 1).append(':').append(start.getCharacter() + 1)
                        .append(excerpt(root, location.getUri(), start.getLine())).append('\n');
            });
            if (definitions.size() > 6) {
                output.append("Additional definitions omitted.\n");
            }
            if (references) {
                var element = ReadActions.compute(() -> {
                    var leaf = source.file().findElementAt(source.offset());
                    return SlangFindUsagesProvider.isIdentifier(leaf) ? leaf : null;
                });
                if (element == null) {
                    output.append("No identifier at the requested position for reference search.\n");
                } else {
                    int[] count = {0};
                    boolean complete = UsageSearch.process(element, GlobalSearchScope.projectScope(project), usage -> {
                        var reference = ReadActions.compute(() -> {
                            var target = usage.getElement();
                            var hint = target == null ? null : target.getContainingFile();
                            var targetFile = hint == null ? null : hint.getVirtualFile();
                            var document = hint == null ? null : PsiDocumentManager.getInstance(project).getDocument(hint);
                            int offset = usage.getNavigationOffset();
                            if (targetFile == null || document == null || offset < 0 || offset > document.getTextLength()) {
                                return null;
                            }
                            int row = document.getLineNumber(offset);
                            return display(root, UsageSearch.fileUri(targetFile).toString()) + ":" + (row + 1)
                                    + ":" + (offset - document.getLineStartOffset(row) + 1);
                        });
                        if (reference == null) {
                            return true;
                        }
                        if (count[0]++ >= MAX_REFERENCES) {
                            return false;
                        }
                        output.append("Reference: ").append(reference).append('\n');
                        return true;
                    });
                    if (!complete) {
                        output.append("References incomplete or truncated.\n");
                    } else if (count[0] == 0) {
                        output.append("No project references found.\n");
                    }
                }
            }
        }
        if (source.document().getModificationStamp() != source.stamp()) {
            return "Source changed during resolution; retry at the current position.";
        }
        return output.toString().stripTrailing();
    }

    private record Source(PsiFile file, Document document, long stamp, int offset) {
    }

    private static Path root(Project project) throws IOException {
        if (project.isDisposed() || project.getBasePath() == null) {
            throw new IllegalArgumentException("Open a local Slang project first.");
        }
        if (!ProjectTrust.isTrusted(project)) {
            throw new IllegalArgumentException("Slang analysis requires a trusted project.");
        }
        return Path.of(project.getBasePath()).toRealPath();
    }

    static Path resolve(Path root, String relative) throws IOException {
        Path path = Path.of(relative);
        if (path.isAbsolute()) {
            throw new IllegalArgumentException("Expected a project-relative path: " + relative);
        }
        var candidate = root.resolve(path).normalize();
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("Path is outside the project: " + relative);
        }
        var real = candidate.toRealPath();
        if (!real.startsWith(root) || !java.nio.file.Files.isRegularFile(real)) {
            throw new IllegalArgumentException("Expected a file inside the project: " + relative);
        }
        return real;
    }

    private static String display(Path root, String uri) {
        try {
            Path path = Path.of(URI.create(uri)).normalize();
            return path.startsWith(root) ? root.relativize(path).toString() : uri;
        } catch (IllegalArgumentException | java.nio.file.FileSystemNotFoundException ignored) {
            return uri;
        }
    }

    private static String excerpt(Path root, String uri, int line) {
        try {
            Path path = Path.of(URI.create(uri)).toRealPath();
            if (!path.startsWith(root)) {
                return "";
            }
            var file = LocalFileSystem.getInstance().findFileByNioFile(path);
            return ReadActions.compute(() -> {
                var document = file == null || file.getFileType() != SlangFileType.INSTANCE
                        ? null : FileDocumentManager.getInstance().getDocument(file);
                if (document == null || line < 0 || line >= document.getLineCount()) {
                    return "";
                }
                int start = document.getLineStartOffset(line);
                int end = Math.min(document.getLineEndOffset(line), start + 180);
                return " — " + document.getCharsSequence().subSequence(start, end).toString().strip();
            });
        } catch (IOException | IllegalArgumentException | java.nio.file.FileSystemNotFoundException ignored) {
            return "";
        }
    }

    private static String clip(String text, int limit) {
        if (text == null) {
            return "no detail available";
        }
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }
}

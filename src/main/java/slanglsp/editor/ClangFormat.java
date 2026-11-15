package slanglsp.editor;

import com.intellij.application.options.CodeStyle;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import slanglsp.settings.SlangCodeStyleSettings;
import slanglsp.settings.BundledClangFormat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** Shared, direct clang-format invocation for explicit formatting and live checks. */
final class ClangFormat {
    record Options(String executable, Path file, String style, String fallback) {}

    static Options options(PsiFile file) {
        var virtualFile = file.getVirtualFile();
        if (virtualFile == null || !virtualFile.isInLocalFileSystem()) return null;
        var state = CodeStyle.getSettings(file).getCustomSettings(SlangCodeStyleSettings.class);
        if (!state.USE_CLANG_FORMAT) return null;
        String executable = state.USE_CUSTOM_CLANG_FORMAT ? state.CLANG_FORMAT_PATH.trim()
                : state.USE_BUNDLED_CLANG_FORMAT ? BundledClangFormat.executable() : null;
        if (executable == null || executable.isBlank()) {
            executable = executable("");
            if (executable == null) executable = SystemInfo.isWindows ? "clang-format.exe" : "clang-format";
        }
        return new Options(executable, virtualFile.toNioPath(), "file", "LLVM");
    }

    private static String executable(String configured) {
        if (!configured.isBlank()) return usable(configured.trim());
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String directory : path.split(java.io.File.pathSeparator)) {
            if (directory.isBlank()) continue;
            try {
                String candidate = usable(Path.of(directory, SystemInfo.isWindows ? "clang-format.exe" : "clang-format").toString());
                if (candidate != null) return candidate;
            } catch (InvalidPathException ignored) {
                // Ignore malformed PATH entries.
            }
        }
        return null;
    }

    private static String usable(String path) {
        try {
            Path candidate = Path.of(path);
            return Files.isRegularFile(candidate) && Files.isExecutable(candidate)
                    ? candidate.toAbsolutePath().toString() : null;
        } catch (InvalidPathException ignored) {
            return null;
        }
    }

    static String style(Options options) {
        if (!options.style.equals("file")) return options.style;
        for (Path directory = options.file.getParent(); directory != null; directory = directory.getParent()) {
            if (Files.isRegularFile(directory.resolve(".clang-format"))
                    || Files.isRegularFile(directory.resolve("_clang-format"))) return "file";
        }
        // clang-format's --fallback-style only accepts named presets, not inline YAML.
        return options.fallback;
    }

    static String format(Options options, String text, List<TextRange> ranges,
            Consumer<CapturingProcessHandler> started) throws ExecutionException, IOException {
        ProgressManager.checkCanceled();
        var command = new GeneralCommandLine(options.executable)
                .withCharset(StandardCharsets.UTF_8)
                .withWorkDirectory(options.file.getParent().toFile())
                .withParameters("--assume-filename=" + options.file, "--style=" + style(options));
        for (TextRange range : ranges) {
            if (range.getStartOffset() == 0 && range.getEndOffset() == text.length()) continue;
            command.addParameters("--offset=" + text.substring(0, range.getStartOffset()).getBytes(StandardCharsets.UTF_8).length,
                    "--length=" + range.substring(text).getBytes(StandardCharsets.UTF_8).length);
        }
        var handler = new CapturingProcessHandler(command);
        started.accept(handler);
        // Drain stdout concurrently with stdin, including for large documents.
        var writer = ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try (var input = handler.getProcessInput()) {
                input.write(text.getBytes(StandardCharsets.UTF_8));
            } catch (IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        });
        try {
            var indicator = ProgressManager.getInstance().getProgressIndicator();
            var output = handler.runProcessWithProgressIndicator(
                    indicator == null ? new EmptyProgressIndicator() : indicator, 10_000, true);
            ProgressManager.checkCanceled();
            if (output.isCancelled()) throw new com.intellij.openapi.progress.ProcessCanceledException();
            if (output.isTimeout()) throw new IOException("clang-format timed out");
            if (output.getExitCode() != 0) throw new IOException(output.getStderr());
            try {
                writer.get();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new com.intellij.openapi.progress.ProcessCanceledException();
            } catch (java.util.concurrent.ExecutionException failure) {
                throw new IOException("Could not send document to clang-format", failure.getCause());
            }
            return output.getStdout();
        } finally {
            handler.destroyProcess();
            writer.cancel(true);
        }
    }
}

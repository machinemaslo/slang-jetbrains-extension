package slanglsp.editor;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;

/** Compatibility for slangd 2026.22: config discovery and discarded clang-format errors. */
final class FormattingConfiguration {
    private static final String STYLE = "slang.format.clangFormatStyle";
    private static final String FALLBACK = "slang.format.clangFormatFallbackStyle";

    private FormattingConfiguration() {
    }

    static void validate(Path source, String executable, Map<String, Object> settings,
            Consumer<CapturingProcessHandler> started) throws Exception {
        String style = (String) settings.get(STYLE);
        if ("file".equals(style)) {
            // slangd checks "_clang_format" instead of clang-format's "_clang-format".
            // An explicit path also makes discovery independent of the server working directory.
            Path configuration = findConfiguration(source.getParent());
            style = configuration == null ? (String) settings.get(FALLBACK) : "file:" + configuration;
        }
        settings.put(STYLE, style);
        // Only validate configuration here. Slangd owns the formatting invocation and Slang-specific edits.
        // Its formatSource currently discards both the exit code and stderr, returning an empty edit list.
        var command = new GeneralCommandLine(executable)
                .withCharset(StandardCharsets.UTF_8)
                .withWorkDirectory(source.getParent().toFile())
                .withParameters("--assume-filename=" + source + ".cs", "--style=" + style, "--dump-config");
        var process = new CapturingProcessHandler(command);
        started.accept(process);
        try {
            var indicator = ProgressManager.getInstance().getProgressIndicator();
            var output = process.runProcessWithProgressIndicator(
                    indicator == null ? new EmptyProgressIndicator() : indicator, 10_000, true);
            ProgressManager.checkCanceled();
            if (output.isCancelled()) {
                throw new ProcessCanceledException();
            }
            if (output.isTimeout()) {
                throw new IOException("clang-format configuration validation timed out");
            }
            if (output.getExitCode() != 0) {
                throw new IOException(output.getStderr());
            }
        } finally {
            process.destroyProcess();
        }
    }

    private static Path findConfiguration(Path directory) {
        for (Path parent = directory; parent != null; parent = parent.getParent()) {
            for (String name : new String[] {".clang-format", "_clang-format"}) {
                Path candidate = parent.resolve(name);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }
}

package slanglsp.editor;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.util.EnvironmentUtil;
import slanglsp.discovery.ExecutableLocator;
import slanglsp.discovery.VcpkgDiscoveryService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import slanglsp.editor.BatchDiagnosticsAnnotator.Problem;

/**
 * Explicit, bounded batch validation. Slangd's unversioned, change-only push diagnostics
 * cannot establish completion for a clean/unchanged file. Use its sibling compiler.
 */
@SuppressWarnings("try") // Timing scopes report elapsed time when closed.
final class BatchDiagnostics {
    private static final int TIMEOUT_MILLIS = 10_000;


    static List<Problem> check(BatchDiagnosticsAnnotator.Input input) throws Exception {
        return check(input, TIMEOUT_MILLIS);
    }

    static List<Problem> check(BatchDiagnosticsAnnotator.Input input, int timeoutMillis) throws Exception {
        ProgressManager.checkCanceled();
        var project = input.file().getProject();
        var state = input.settings();
        Path compiler;
        try (@SuppressWarnings("unused") var timing = AnalysisTiming.start("compiler discovery", input.path().toString())) {
            String server = ExecutableLocator.find(state.explicitSlangdLocation, state.useLocalVcpkgSlangd,
                    () -> project.getService(VcpkgDiscoveryService.class).findExecutable(),
                    EnvironmentUtil.getValue("PATH"), EnvironmentUtil.getValue("VULKAN_SDK"),
                    SystemInfo.isWindows ? "slangd.exe" : "slangd", project.getBasePath())
                    .orElseThrow(() -> new IOException("Slang installation not found"));
            compiler = Path.of(server).toRealPath().resolveSibling(SystemInfo.isWindows ? "slangc.exe" : "slangc");
            if (!Files.isRegularFile(compiler) || !Files.isExecutable(compiler)) {
                throw new IOException("Slang compiler not found: " + compiler);
            }
        }
        var command = new GeneralCommandLine(compiler.toString()).withCharset(StandardCharsets.UTF_8)
                .withWorkDirectory(input.path().getParent().toFile())
                .withParameters("-lang", "slang", "-no-codegen", "-enable-machine-readable-diagnostics");
        input.searchPaths().forEach(path -> {
            Path include = Path.of(path);
            if (!include.isAbsolute() && project.getBasePath() != null) {
                include = Path.of(project.getBasePath()).resolve(include);
            }
            command.addParameters("-I", include.toString());
        });
        state.predefinedMacros.stream().filter(macro -> !macro.isBlank())
                .forEach(macro -> command.addParameter("-D" + macro));
        command.addParameters("--", "-");
        try (@SuppressWarnings("unused") var timing = AnalysisTiming.start("compiler execution", input.path().toString())) {
            return run(command, input.text(), timeoutMillis);
        }
    }

    static List<Problem> run(GeneralCommandLine command, String source, int timeoutMillis) throws Exception {
        ProgressManager.checkCanceled();
        CapturingProcessHandler handler;
        try (@SuppressWarnings("unused") var timing = AnalysisTiming.start("compiler process startup", command.getExePath())) {
            handler = new CapturingProcessHandler(command);
        }
        var writer = ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try (var stream = handler.getProcessInput()) {
                stream.write(source.getBytes(StandardCharsets.UTF_8));
            } catch (IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        });
        try {
            var indicator = ProgressManager.getInstance().getProgressIndicator();
            var output = handler.runProcessWithProgressIndicator(
                    indicator == null ? new EmptyProgressIndicator() : indicator, timeoutMillis, true);
            ProgressManager.checkCanceled();
            if (output.isCancelled()) throw new ProcessCanceledException();
            if (output.isTimeout()) throw new IOException("Slang analysis timed out");
            if (output.getExitCode() == 0) {
                try {
                    writer.get();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ProcessCanceledException();
                }
            }
            String diagnostics = output.getStderr() + output.getStdout();
            var problems = parse(diagnostics);
            if (output.getExitCode() != 0 && problems.stream().noneMatch(p -> p.severity() == HighlightSeverity.ERROR)) {
                throw new IOException(diagnostics.isBlank() ? "Slang compiler exited with code " + output.getExitCode()
                        : diagnostics.strip());
            }
            return problems;
        } finally {
            handler.destroyProcess();
            writer.cancel(true);
        }
    }

    static List<Problem> parse(String diagnostics) throws IOException {
        var problems = new ArrayList<Problem>();
        for (String line : diagnostics.lines().toList()) {
            if (line.isBlank()) continue;
            String[] fields = line.split("\t", 8);
            if (fields.length != 8) throw new IOException("Unrecognized Slang diagnostic: " + line);
            HighlightSeverity severity = switch (fields[1]) {
                case "error", "fatal" -> HighlightSeverity.ERROR;
                case "warning" -> HighlightSeverity.WARNING;
                case "note", "help" -> HighlightSeverity.WEAK_WARNING;
                default -> null;
            };
            if (fields[1].equals("span")) {
                // The primary span carries the detailed message (e.g. the missing identifier).
                if (!problems.isEmpty()) {
                    var previous = problems.getLast();
                    if (previous.file().equals(fields[2]) && previous.line() == Integer.parseInt(fields[3])
                            && previous.column() == Integer.parseInt(fields[4])) {
                        problems.set(problems.size() - 1, new Problem(previous.file(), previous.line(), previous.column(),
                                previous.endLine(), previous.endColumn(), previous.severity(), fields[7]));
                    }
                }
            } else if (severity != null) {
                problems.add(new Problem(fields[2], Integer.parseInt(fields[3]), Integer.parseInt(fields[4]),
                        Integer.parseInt(fields[5]), Integer.parseInt(fields[6]), severity, fields[7]));
            }
        }
        return List.copyOf(problems);
    }
}

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
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Compatibility for slangd 2026.22: config discovery and discarded clang-format errors. */
final class FormattingConfiguration {
    private static final String STYLE = "slang.format.clangFormatStyle";
    private static final String FALLBACK = "slang.format.clangFormatFallbackStyle";
    private static final int MAX_CACHED = 64;
    private static final int MAX_CONFIG_BYTES = 1024 * 1024;
    private static final LinkedHashMap<Validation, Boolean> VALIDATED = new LinkedHashMap<>(16, 0.75f, true);

    private record ConfigRevision(Path path, String digest) {
    }

    private record Validation(Path directory, Path executable, FileTime modified, FileTime created, long size,
                              Object fileKey, String style, List<ConfigRevision> configs) {
    }

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
        ProgressManager.checkCanceled();
        Validation revision = revision(source.getParent(), executable, style);
        synchronized (VALIDATED) {
            if (revision != null && VALIDATED.get(revision) != null) {
                return;
            }
        }
        validateUncached(source, executable, style, started);
        if (revision != null && revision.equals(revision(source.getParent(), executable, style))) {
            synchronized (VALIDATED) {
                VALIDATED.put(revision, Boolean.TRUE);
                if (VALIDATED.size() > MAX_CACHED) {
                    VALIDATED.pollFirstEntry();
                }
            }
        }
    }

    private static Validation revision(Path directory, String executable, String style) {
        if (style == null || !style.startsWith("file:") && style.contains("InheritParentConfig")) {
            return null;
        }
        try {
            Path binary = Path.of(executable).toRealPath();
            var attributes = Files.readAttributes(binary, BasicFileAttributes.class);
            var configs = new ArrayList<ConfigRevision>();
            if (style.startsWith("file:")) {
                Path requested = directory.resolve(style.substring(5));
                Path config = requested.toRealPath();
                configs.add(fingerprint(config));
                // InheritParentConfig can depend on both the explicit config location and the source tree.
                fingerprintAncestors(requested.getParent(), configs);
                if (!config.getParent().equals(requested.getParent())) {
                    fingerprintAncestors(config.getParent(), configs);
                }
                if (!directory.equals(requested.getParent())) {
                    fingerprintAncestors(directory, configs);
                }
            }
            return new Validation(directory, binary, attributes.lastModifiedTime(), attributes.creationTime(),
                    attributes.size(), attributes.fileKey(), style, List.copyOf(configs));
        } catch (IOException | IllegalArgumentException ignored) {
            // Let clang-format report missing or invalid files; don't cache ambiguous inputs.
            return null;
        }
    }

    private static void fingerprintAncestors(Path directory, List<ConfigRevision> configs) throws IOException {
        for (Path parent = directory; parent != null; parent = parent.getParent()) {
            for (String name : List.of(".clang-format", "_clang-format")) {
                Path file = parent.resolve(name);
                if (Files.isRegularFile(file)) {
                    configs.add(fingerprint(file));
                }
            }
        }
    }

    private static ConfigRevision fingerprint(Path file) throws IOException {
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(MAX_CONFIG_BYTES + 1);
            if (bytes.length > MAX_CONFIG_BYTES) {
                throw new IOException("clang-format config exceeds validation cache limit");
            }
            var digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return new ConfigRevision(file, HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void validateUncached(Path source, String executable, String style,
            Consumer<CapturingProcessHandler> started) throws Exception {
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

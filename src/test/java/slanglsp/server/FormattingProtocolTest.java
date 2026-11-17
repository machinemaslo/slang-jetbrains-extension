package slanglsp.server;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import slanglsp.TestDependencies;
import slanglsp.settings.ProjectSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static slanglsp.FormattingFixtures.text;
import static slanglsp.FormattingFixtures.withoutFinalNewline;

/** Upstream contract: real slangd, real clang-format, and real LSP configuration/edits. */
@Timeout(90)
class FormattingProtocolTest {
    private static final String INPUT = text("simple-function/before.slang");
    private static final String SLANG = text("simple-function/after.allman-four.slang");
    private static final String ATTACH = text("simple-function/after.attach-two.slang");

    @TempDir
    Path root;

    @ParameterizedTest
    @ValueSource(strings = {"project", "parent", "explicit", "inline", "fallback"})
    void configurationSourcesControlFormatting(String source) throws Exception {
        var settings = settings();
        String expected = ATTACH;
        Path file = root.resolve("nested/sample.slang");
        String configuration = text("configuration/external/.clang-format");
        switch (source) {
            case "project" -> write("nested/.clang-format", configuration);
            case "parent" -> write(".clang-format", configuration);
            case "explicit" -> {
                Path config = write("configuration with spaces/custom-format.yaml", configuration);
                settings.put("slang.format.clangFormatStyle", "file:" + config.toAbsolutePath());
            }
            case "inline" -> settings.put("slang.format.clangFormatStyle",
                    withoutFinalNewline("configuration/inline/two-spaces.yaml"));
            case "fallback" -> expected = SLANG;
            default -> throw new AssertionError(source);
        }
        try (var server = new FormattingServer(root, settings, null)) {
            assertEquals(expected, server.format(file, INPUT, null));
            assertEquals(expected, server.format(file, expected, null), "Second formatting pass must not change output");
        }
    }

    @ParameterizedTest
    @MethodSource("fallbackStyles")
    void explicitFallbackIsNotReplacedWithADifferentPreset(String style) throws Exception {
        var settings = settings();
        settings.put("slang.format.clangFormatFallbackStyle", style);
        try (var server = new FormattingServer(root, settings, null)) {
            String expected = text(style.equals("LLVM")
                    ? "simple-function/after.llvm.slang" : "simple-function/after.attach-three.slang");
            assertEquals(expected, server.format(root.resolve("sample.slang"), INPUT, null));
        }
    }

    static Stream<String> fallbackStyles() {
        return Stream.of("LLVM", withoutFinalNewline("configuration/inline/three-spaces.yaml"));
    }

    @Test
    void configuredExecutableMayLiveInAPathWithSpaces() throws Exception {
        Path binary = copiedExecutable();
        var settings = settings();
        settings.put("slang.format.clangFormatLocation", binary.toString());
        try (var server = new FormattingServer(root, settings, null)) {
            assertEquals(SLANG, server.format(root.resolve("sample.slang"), INPUT, null));
        }
    }

    @Test
    void clangFormatCanBeDiscoveredOnTheServerPath() throws Exception {
        Path binary = copiedExecutable();
        var settings = settings();
        settings.put("slang.format.clangFormatLocation", "");
        String inherited = System.getenv().getOrDefault("PATH", "");
        String path = binary.getParent() + java.io.File.pathSeparator + inherited;
        try (var server = new FormattingServer(root, settings, path)) {
            assertEquals(SLANG, server.format(root.resolve("sample.slang"), INPUT, null));
        }
    }

    @Test
    void shaderGoldenOutputPreservesAttributesAndPublicDeclarations() throws Exception {
        try (var server = new FormattingServer(root, settings(), null)) {
            String expected = text("shader/after.slangd.slang");
            Path file = root.resolve("shader.slang");
            assertEquals(expected, server.format(file, text("shader/before.slang"), null));
            assertEquals(expected, server.format(file, expected, null));
        }
    }

    @Test
    void interfaceAndNestedGenericGoldenOutputIsSupportedUpstream() throws Exception {
        try (var server = new FormattingServer(root, settings(), null)) {
            assertEquals(text("types/after.slang"),
                    server.format(root.resolve("types.slang"), text("types/before.slang"), null));
        }
    }

    @Test
    void rangeFormattingUsesUnsavedTextAndUtf16Positions() throws Exception {
        Path file = write("range.slang", text("protocol-selection/on-disk.slang"));
        String unsaved = text("protocol-selection/before.slang");
        String expected = text("protocol-selection/after.slang");
        try (var server = new FormattingServer(root, settings(), null)) {
            assertEquals(expected, server.format(file, unsaved, new Range(new Position(3, 0), new Position(3, 15))));
            assertEquals(text("protocol-selection/on-disk.slang"), Files.readString(file),
                    "Formatting must not save the document");
        }
    }

    @Test
    void spirvAssemblyIsExcludedRatherThanReformattedAsCpp() throws Exception {
        String assembly = withoutFinalNewline("assembly/preserved-block.slang");
        String input = text("assembly/before.slang");
        try (var server = new FormattingServer(root, settings(), null)) {
            String output = server.format(root.resolve("assembly.slang"), input, null);
            assertTrue(output.contains(assembly), output);
            assertEquals(output, server.format(root.resolve("assembly.slang"), output, null));
        }
    }

    @Test
    void invalidConfigurationDoesNotSilentlyFormatWithAFallback() throws Exception {
        write(".clang-format", text("configuration/invalid/.clang-format"));
        try (var server = new FormattingServer(root, settings(), null)) {
            assertEquals(INPUT, server.format(root.resolve("invalid.slang"), INPUT, null));
        }
    }

    @Test
    void missingExecutableDoesNotProduceDestructiveEdits() throws Exception {
        var settings = settings();
        settings.put("slang.format.clangFormatLocation", root.resolve("not-installed").toString());
        try (var server = new FormattingServer(root, settings, null)) {
            assertEquals(INPUT, server.format(root.resolve("missing.slang"), INPUT, null));
        }
    }

    private Map<String, Object> settings() {
        var state = new ProjectSettings.State();
        state.clangFormatLocation = TestDependencies.clangFormat();
        state.allowLineBreakChangesInRangeFormatting = true;
        return state.createServerSettings();
    }

    private Path copiedExecutable() throws Exception {
        Path installed = Path.of(TestDependencies.clangFormat());
        Path binary = root.resolve("tools with spaces").resolve(installed.getFileName());
        Files.createDirectories(binary.getParent());
        Files.copy(installed, binary, StandardCopyOption.REPLACE_EXISTING);
        assertTrue(binary.toFile().setExecutable(true));
        return binary;
    }

    private Path write(String relative, String text) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }

    private static final class FormattingServer implements AutoCloseable {
        private final Process process;
        private final Future<?> listening;
        private final LanguageServer server;
        private int version;

        FormattingServer(Path root, Map<String, Object> settings, String path) throws Exception {
            var configurationRead = new CompletableFuture<Void>();
            var client = new FormattingClient(settings, configurationRead);
            var builder = new ProcessBuilder(TestDependencies.slangd())
                    .directory(root.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            if (path != null) {
                builder.environment().put("PATH", path);
            }
            process = builder.start();
            var launcher = LSPLauncher.createClientLauncher(
                    client, new ResponseInputStream(process.getInputStream()), process.getOutputStream());
            listening = launcher.startListening();
            server = launcher.getRemoteProxy();
            try {
                var params = new InitializeParams();
                var capabilities = new ClientCapabilities();
                var workspace = new WorkspaceClientCapabilities();
                workspace.setConfiguration(true);
                capabilities.setWorkspace(workspace);
                params.setCapabilities(capabilities);
                ClientFeatures.configureWorkspace(params, root.toString(), "Formatting");
                var initialized = server.initialize(params).get(20, TimeUnit.SECONDS);
                assertTrue(initialized.getCapabilities().getDocumentFormattingProvider().getLeft());
                server.initialized(new InitializedParams());
                configurationRead.get(20, TimeUnit.SECONDS);
                server.getWorkspaceService().didChangeConfiguration(new DidChangeConfigurationParams(settings));
            } catch (Exception | AssertionError failure) {
                close();
                throw failure;
            }
        }

        String format(Path file, String text, Range range) throws Exception {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                Files.writeString(file, text);
            }
            String uri = file.toUri().toString();
            var identifier = new TextDocumentIdentifier(uri);
            server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                    new TextDocumentItem(uri, "slang", ++version, text)));
            try {
                var options = new FormattingOptions(4, true);
                List<? extends TextEdit> edits = range == null
                        ? server.getTextDocumentService().formatting(
                                new DocumentFormattingParams(identifier, options)).get(20, TimeUnit.SECONDS)
                        : server.getTextDocumentService().rangeFormatting(
                                new DocumentRangeFormattingParams(identifier, options, range)).get(20, TimeUnit.SECONDS);
                if (edits == null) {
                    return text;
                }
                var ordered = new ArrayList<>(edits);
                ordered.sort(Comparator.comparingInt((TextEdit edit) -> offset(text, edit.getRange().getStart())).reversed());
                var output = new StringBuilder(text);
                int previousStart = text.length();
                for (var edit : ordered) {
                    int start = offset(text, edit.getRange().getStart());
                    int end = offset(text, edit.getRange().getEnd());
                    assertTrue(start <= end && end <= previousStart, "LSP edits must be valid and non-overlapping");
                    output.replace(start, end, edit.getNewText());
                    previousStart = start;
                }
                return output.toString();
            } finally {
                server.getTextDocumentService().didClose(new DidCloseTextDocumentParams(identifier));
            }
        }

        private static int offset(String text, Position position) {
            int start = 0;
            for (int line = 0; line < position.getLine(); line++) {
                int newline = text.indexOf('\n', start);
                assertTrue(newline >= 0, "Invalid LSP line: " + position);
                start = newline + 1;
            }
            int offset = start + position.getCharacter();
            int newline = text.indexOf('\n', start);
            assertTrue(offset <= (newline < 0 ? text.length() : newline), "Invalid LSP character: " + position);
            return offset;
        }

        @Override
        public void close() {
            try {
                if (process.isAlive()) {
                    server.shutdown().get(3, TimeUnit.SECONDS);
                    server.exit();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // A broken transport must still be killed and reaped; do not mask the original assertion.
            } finally {
                process.destroyForcibly();
                listening.cancel(true);
            }
            try {
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "slangd process did not terminate");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }
    }

    private record FormattingClient(Map<String, Object> settings, CompletableFuture<Void> configurationRead)
            implements LanguageClient {
        @Override
        public CompletableFuture<List<Object>> configuration(ConfigurationParams params) {
            configurationRead.complete(null);
            return CompletableFuture.completedFuture(ServerConfiguration.select(settings, params));
        }

        @Override
        public CompletableFuture<Void> registerCapability(RegistrationParams params) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void telemetryEvent(Object object) {
        }

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams params) {
        }

        @Override
        public void showMessage(MessageParams params) {
        }

        @Override
        public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void logMessage(MessageParams params) {
        }
    }
}

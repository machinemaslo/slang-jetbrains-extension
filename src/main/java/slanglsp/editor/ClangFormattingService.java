package slanglsp.editor;

import com.intellij.application.options.CodeStyle;
import com.intellij.formatting.service.AsyncDocumentFormattingService;
import com.intellij.formatting.service.AsyncFormattingRequest;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import com.redhat.devtools.lsp4ij.LSPIJUtils;
import com.redhat.devtools.lsp4ij.LanguageServerItem;
import com.redhat.devtools.lsp4ij.features.formatting.LSPFormattingParams;
import com.redhat.devtools.lsp4ij.features.formatting.LSPFormattingSupport;
import org.eclipse.lsp4j.FormattingOptions;
import org.eclipse.lsp4j.TextEdit;
import org.jetbrains.annotations.NotNull;
import slanglsp.language.SlangLanguage;
import slanglsp.settings.SlangCodeStyleSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Routes explicit external formatting through slangd; the platform owns edit application. */
public final class ClangFormattingService extends AsyncDocumentFormattingService {
    @Override
    public @NotNull Set<Feature> getFeatures() {
        return Set.of(Feature.FORMAT_FRAGMENTS);
    }

    @Override
    public boolean canFormat(@NotNull PsiFile file) {
        return file.getLanguage().isKindOf(SlangLanguage.INSTANCE)
                && file.getVirtualFile() != null && file.getVirtualFile().isInLocalFileSystem()
                && CodeStyle.getSettings(file).getCustomSettings(SlangCodeStyleSettings.class).USE_CLANG_FORMAT;
    }

    @Override
    protected FormattingTask createFormattingTask(@NotNull AsyncFormattingRequest request) {
        var file = request.getContext().getContainingFile();
        var settings = request.getContext().getCodeStyleSettings();
        var serverSettings = slanglsp.server.ServerClient.settings(file.getProject(), settings);
        String executable = settings.getCustomSettings(SlangCodeStyleSettings.class).clangFormatExecutable();
        var indent = settings.getIndentOptions(file.getFileType());
        var options = new FormattingOptions(indent.INDENT_SIZE, !indent.USE_TAB_CHARACTER);
        var document = EditorFactory.getInstance().createDocument(request.getDocumentText());
        var ranges = List.copyOf(request.getFormattingRanges());
        var source = file.getVirtualFile().toNioPath();
        var support = new FormattingSupport(file);
        var cancelled = new AtomicBoolean();
        var process = new java.util.concurrent.atomic.AtomicReference<com.intellij.execution.process.CapturingProcessHandler>();
        return new FormattingTask() {
            @Override
            @SuppressWarnings("try") // Closing the scopes restores the deadline and server configuration.
            public void run() {
                try (@SuppressWarnings("unused") var deadline = AnalysisDeadline.open()) {
                    if (cancelled.get()) {
                        return;
                    }
                    if (executable == null || executable.isBlank()) {
                        throw new IllegalStateException("Selected clang-format executable is unavailable");
                    }
                    if ((executable.contains("/") || executable.contains("\\"))
                            && !java.nio.file.Files.isExecutable(java.nio.file.Path.of(executable))) {
                        throw new IllegalStateException("clang-format executable is unavailable: " + executable);
                    }
                    FormattingConfiguration.validate(source, executable, serverSettings, handler -> {
                        process.set(handler);
                        if (cancelled.get()) {
                            handler.destroyProcess();
                        }
                    });
                    String formatted;
                    try (@SuppressWarnings("unused") var configuration =
                                 file.getProject().getService(slanglsp.server.FormattingSettings.class)
                            .open(serverSettings, cancelled::get)) {
                        if (cancelled.get()) {
                            return;
                        }
                        var servers = AnalysisDeadline.await(ReadActions.compute(support::connect));
                        var server = servers.stream()
                                .filter(item -> item.getServerDefinition().getId().equals("slanglsp.SlangLanguageServer"))
                                .findFirst().orElseThrow(() -> new IllegalStateException("Slang language server is unavailable"));
                        server.getWorkspaceService().didChangeConfiguration(
                                new org.eclipse.lsp4j.DidChangeConfigurationParams(serverSettings));
                        var edits = new ArrayList<TextEdit>();
                        for (TextRange range : ranges) {
                            if (cancelled.get()) {
                                return;
                            }
                            TextRange selection = range.getStartOffset() == 0 && range.getEndOffset() == document.getTextLength()
                                    ? null : range;
                            var params = new LSPFormattingParams(selection, document, server, options);
                            var result = AnalysisDeadline.await(ReadActions.compute(() -> support.getFeatureData(params)));
                            if (result != null) {
                                // slangd 2026.22 passes a zero-based start line to clang-format's
                                // one-based --lines option. Preserve the IDE's selected-range boundary.
                                result.stream().filter(edit -> selection == null || inside(edit, selection, document))
                                        .filter(edit -> !edits.contains(edit)).forEach(edits::add);
                            }
                        }
                        formatted = LSPIJUtils.applyEdits(document, edits);
                    }
                    if (!cancelled.get()) {
                        request.onTextReady(formatted.equals(document.getText()) ? null : formatted);
                    }
                } catch (ProcessCanceledException failure) {
                    support.cancel();
                    throw failure;
                } catch (Exception failure) {
                    support.cancel();
                    if (!cancelled.get()) {
                        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                        request.onError(getName(), cause.getMessage() == null ? cause.toString() : cause.getMessage());
                    }
                }
            }

            @Override
            public boolean cancel() {
                cancelled.set(true);
                support.cancel();
                var handler = process.get();
                if (handler != null) {
                    handler.destroyProcess();
                }
                return true;
            }

            @Override
            public boolean isRunUnderProgress() {
                return true;
            }
        };
    }

    private static boolean inside(TextEdit edit, TextRange range, com.intellij.openapi.editor.Document document) {
        int start = LSPIJUtils.toOffset(edit.getRange().getStart(), document);
        int end = LSPIJUtils.toOffset(edit.getRange().getEnd(), document);
        return start >= range.getStartOffset() && start < range.getEndOffset() && end <= range.getEndOffset();
    }

    private static final class FormattingSupport extends LSPFormattingSupport {
        private FormattingSupport(PsiFile file) {
            super(file);
        }

        private CompletableFuture<List<LanguageServerItem>> connect() {
            return getLanguageServers(getFile(), null, null);
        }
    }

    @Override
    protected @NotNull String getNotificationGroupId() {
        return "Slang LSP";
    }

    @Override
    protected @NotNull String getName() {
        return "Slang clang-format";
    }
}

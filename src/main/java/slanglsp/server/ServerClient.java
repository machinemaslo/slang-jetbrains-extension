package slanglsp.server;

import slanglsp.settings.ProjectSettings;

import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl;
import org.jetbrains.annotations.NotNull;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ServerClient extends LanguageClientImpl {
    private static final com.intellij.openapi.util.Key<java.util.concurrent.atomic.AtomicLong> ANALYSIS_GENERATION =
            com.intellij.openapi.util.Key.create("slang.server.analysisGeneration");

    public static void notifySettingsChanged(Project project) {
        invalidateAnalysis(project);
        for (var client : activeClients)
            if (client.project == project)
                client.triggerChangeConfiguration();
    }

    public static long analysisGeneration(Project project) {
        var generation = project.getUserData(ANALYSIS_GENERATION);
        return generation == null ? 0 : generation.get();
    }

    private static void invalidateAnalysis(Project project) {
        synchronized (ANALYSIS_GENERATION) {
            var generation = project.getUserData(ANALYSIS_GENERATION);
            if (generation == null) {
                project.putUserData(ANALYSIS_GENERATION, new java.util.concurrent.atomic.AtomicLong(1));
            } else {
                generation.incrementAndGet();
            }
        }
    }

    static final Set<ServerClient> activeClients = ConcurrentHashMap.newKeySet();

    final Project project;
    ServerClient(Project project) {
        super(project);
        this.project = project;
    }

    @Override
    public synchronized void handleServerStatusChanged(@NotNull com.redhat.devtools.lsp4ij.ServerStatus serverStatus) {
        if (isDisposed() || project.isDisposed())
            return;

        invalidateAnalysis(project);
        if (serverStatus == com.redhat.devtools.lsp4ij.ServerStatus.started) {
            activeClients.add(this);
            triggerChangeConfiguration();
        }

    }

    @Override
    public synchronized void dispose() {
        activeClients.remove(this);

        if (!isDisposed()) {
            invalidateAnalysis(project);
            project.getService(BuiltinFiles.class).clear();
            super.dispose();
        }
    }

    @Override
    public @NotNull Map<String, Object> createSettings() {
        var settings = settings(project, com.intellij.application.options.CodeStyle.getSettings(project));
        project.getService(FormattingSettings.class).apply(settings);
        return settings;
    }

    public static Map<String, Object> settings(Project project, com.intellij.psi.codeStyle.CodeStyleSettings codeStyle) {
        var state = ProjectSettings.getInstance(project).getState();

        var settings = state.createServerSettings();
        var formatting = codeStyle.getCustomSettings(slanglsp.settings.SlangCodeStyleSettings.class);
        if (formatting.USE_CLANG_FORMAT) {
            String executable = formatting.clangFormatExecutable();
            settings.put("slang.format.clangFormatLocation", executable == null ? "" : executable);
        }
        if (state.clangFormatFallbackStyle.isBlank()) {
            settings.put("slang.format.clangFormatFallbackStyle", slanglsp.settings.ClangFormatStyle.clangFormatStyle(
                    codeStyle));
        }
        return settings;
    }

    @Override
    protected Object findSettings(String section) {
        var settings = createSettings();
        return section == null || section.isEmpty() ? settings : settings.get(section);
    }

    @Override
    public java.util.concurrent.CompletableFuture<java.util.List<Object>> configuration(
            org.eclipse.lsp4j.ConfigurationParams params) {
        // Upstream's supplyAsync uses the same common pool that commit passes occupy.
        // Keep its section-selection hook, but schedule replies independently.
        return project.getService(slanglsp.editor.BatchAnalysisService.class).callback(() -> {
            java.util.List<Object> values = new java.util.ArrayList<>(params.getItems().size());
            for (var item : params.getItems()) {
                values.add(findSettings(item.getSection()));
            }
            return values;
        });
    }

    @Override
    public java.util.concurrent.CompletableFuture<Void> registerCapability(
            org.eclipse.lsp4j.RegistrationParams params) {
        return project.getService(slanglsp.editor.BatchAnalysisService.class).callback(() -> {
            BatchConnection.wrapper(project).registerCapability(params);
            return null;
        });
    }

    @Override
    public java.util.concurrent.CompletableFuture<Void> unregisterCapability(
            org.eclipse.lsp4j.UnregistrationParams params) {
        return project.getService(slanglsp.editor.BatchAnalysisService.class).callback(() -> {
            BatchConnection.wrapper(project).unregisterCapability(params);
            return null;
        });
    }

    @Override
    @SuppressWarnings("UnstableApiUsage") // LSP4IJ supplies workspace folders through its client feature API.
    public java.util.concurrent.CompletableFuture<java.util.List<org.eclipse.lsp4j.WorkspaceFolder>> workspaceFolders() {
        return project.getService(slanglsp.editor.BatchAnalysisService.class).callback(
                () -> getClientFeatures().getWorkspaceFolderFeature().getInitialWorkspaceFolders(project));
    }

    public synchronized void triggerChangeConfiguration() {
        if (!isDisposed() && !project.isDisposed() && activeClients.contains(this)) {
            super.triggerChangeConfiguration();
        }
    }
}

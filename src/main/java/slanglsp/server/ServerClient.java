package slanglsp.server;

import slanglsp.settings.ProjectSettings;

import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl;
import org.jetbrains.annotations.NotNull;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ServerClient extends LanguageClientImpl {
    public static void notifySettingsChanged(Project project) {
        for (var client : activeClients)
            if (client.project == project)
                client.triggerChangeConfiguration();
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

        if (serverStatus == com.redhat.devtools.lsp4ij.ServerStatus.started) {
            activeClients.add(this);
            triggerChangeConfiguration();
        }

    }

    @Override
    public synchronized void dispose() {
        activeClients.remove(this);

        if (!isDisposed()) {
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

    public synchronized void triggerChangeConfiguration() {
        if (!isDisposed() && !project.isDisposed() && activeClients.contains(this)) {
            super.triggerChangeConfiguration();
        }
    }
}

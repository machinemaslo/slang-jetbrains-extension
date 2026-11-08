package slanglsp.server;

import slanglsp.settings.ProjectSettings;

import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl;
import org.jetbrains.annotations.NotNull;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ServerClient extends LanguageClientImpl
{
    public static void notifySettingsChanged(Project project) {
        for (var client : activeClients)
            if (client.project == project) client.triggerChangeConfiguration();
    }

    static final Set<ServerClient> activeClients = ConcurrentHashMap.newKeySet();

    final Project project;
    ServerClient(Project project)
    {
        super(project);
        this.project = project;

    }

    @Override
    public synchronized void handleServerStatusChanged(@NotNull com.redhat.devtools.lsp4ij.ServerStatus serverStatus)
    {
        if (isDisposed() || project.isDisposed()) return;
        if (serverStatus == com.redhat.devtools.lsp4ij.ServerStatus.started)
        {
            activeClients.add(this);
            triggerChangeConfiguration();
        }
        if(serverStatus == com.redhat.devtools.lsp4ij.ServerStatus.stopped)
        {
            activeClients.remove(this);
            project.getService(BuiltinFiles.class).clear();
        }
    }

    @Override
    public synchronized void dispose() {
        activeClients.remove(this);
        if (!isDisposed()) super.dispose();
    }

    @Override
    public @NotNull Map<String, Object> createSettings()
    {
        var state = ProjectSettings.getInstance(project).getState();
        return state.createServerSettings();
    }

    @Override
    public java.util.concurrent.CompletableFuture<List<Object>> configuration(org.eclipse.lsp4j.ConfigurationParams params)
    {
        // Resolve all requested sections from one snapshot. Do not pass Gson objects
        // between plugin classloaders or rely on LSP4IJ's JsonObject instanceof check.
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            var settings = createSettings();
            com.intellij.openapi.diagnostic.Logger.getInstance(ServerClient.class).info(
                    "Slang configuration: search paths=" + settings.get("slang.additionalSearchPaths")
                            + ", search workspace=" + settings.get("slang.searchInAllWorkspaceDirectories"));
            return ServerConfiguration.select(settings, params);
        });
    }

    public synchronized void triggerChangeConfiguration()
    {
        if (!isDisposed() && !project.isDisposed() && activeClients.contains(this)) {
            super.triggerChangeConfiguration();
        }
    }


}

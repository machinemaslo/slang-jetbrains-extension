package slanglsp;

import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;

class SlangLanguageClient extends LanguageClientImpl
{
    static LinkedBlockingDeque<SlangLanguageClient> maybeAliveClients = new LinkedBlockingDeque<>();

    Project project;
    SlangLanguageClient(Project project)
    {
        super(project);
        this.project = project;
        maybeAliveClients.add(this);

    }

    @Override
    public void handleServerStatusChanged(com.redhat.devtools.lsp4ij.ServerStatus serverStatus)
    {
        if (serverStatus == com.redhat.devtools.lsp4ij.ServerStatus.started)
        {
            triggerChangeConfiguration();
        }
        if(serverStatus == com.redhat.devtools.lsp4ij.ServerStatus.stopped)
        {
            maybeAliveClients.remove(this);
        }
    }

    @Override
    public Map<String, Object> createSettings()
    {
        var state = SlangPersistentStateConfig.getInstance(project).getState();
        return state.createServerSettings();
    }

    @Override
    public java.util.concurrent.CompletableFuture<List<Object>> configuration(org.eclipse.lsp4j.ConfigurationParams params)
    {
        // Resolve all requested sections from one snapshot. Do not pass Gson objects
        // between plugin classloaders or rely on LSP4IJ's JsonObject instanceof check.
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            var settings = createSettings();
            com.intellij.openapi.diagnostic.Logger.getInstance(SlangLanguageClient.class).info(
                    "Slang configuration: search paths=" + settings.get("slang.additionalSearchPaths")
                            + ", search workspace=" + settings.get("slang.searchInAllWorkspaceDirectories"));
            return SlangServerConfiguration.select(settings, params);
        });
    }

    public void triggerChangeConfiguration()
    {
        super.triggerChangeConfiguration();
    }


}

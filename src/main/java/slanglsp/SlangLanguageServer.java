package slanglsp;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.util.EnvironmentUtil;
import com.redhat.devtools.lsp4ij.LanguageServerManager;
import com.redhat.devtools.lsp4ij.server.ProcessStreamConnectionProvider;
import java.io.InputStream;
import java.util.List;

class SlangLanguageServer extends ProcessStreamConnectionProvider
{
    private InputStream responseSource;
    private InputStream responses;

    @Override
    public synchronized InputStream getInputStream()
    {
        InputStream source = super.getInputStream();
        if (source == null) return null;
        if (source != responseSource) {
            responseSource = source;
            responses = new SlangResponseInputStream(source);
        }
        return responses;
    }

    Project project;
    SlangLanguageServer(Project project)
    {
        this.project = project;

        var config = SlangPersistentStateConfig.getInstance(project).getState();
        var exePath = SlangExecutableLocator.find(config.explicitSlangdLocation,
                config.useLocalVcpkgSlangd,
                () -> project.getService(VcpkgSlangdService.class).findExecutable(),
                EnvironmentUtil.getValue("PATH"), getLspExeName());
        if (exePath.isPresent())
        {
            super.setCommands(List.of(exePath.get()));
            super.setWorkingDirectory(project.getBasePath());
        }
        else
        {
            NotificationGroupManager.getInstance().getNotificationGroup("Slang LSP").createNotification(
                "Slang LSP",
                "slangd was not found in the configured directory, local vcpkg packages (when enabled), or PATH. Install the shader-slang vcpkg package, or set the slangd directory in Settings | Tools | Slang.",
                NotificationType.ERROR
            ).notify(project);
            LanguageServerManager.getInstance(project).stop("slanglsp.SlangLanguageServer");
        }
    }

    static String getLspExeName()
    {
        if (SystemInfo.isWindows)
            return "slangd.exe";
        return "slangd";
    }

}

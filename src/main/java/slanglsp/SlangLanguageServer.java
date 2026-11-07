package slanglsp;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.util.EnvironmentUtil;
import com.redhat.devtools.lsp4ij.LanguageServerManager;
import com.redhat.devtools.lsp4ij.server.ProcessStreamConnectionProvider;
import java.util.List;
import java.util.Objects;
import java.io.InputStream;

class SlangLanguageServer extends ProcessStreamConnectionProvider
{
    private InputStream responseSource;
    private InputStream responses;

    @Override
    public synchronized InputStream getInputStream() {
        InputStream source = super.getInputStream();
        if (source == null) return null;
        if (source != responseSource) {
            responseSource = source;
            responses = new SlangResponseInputStream(source);
        }
        return responses;
    }

    Project project;
    @Override public void start() throws com.redhat.devtools.lsp4ij.server.CannotStartProcessException {
        super.start();
        // The superclass rejects null or empty commands before starting the process.
        var commands = Objects.requireNonNull(getCommands(), "Commands must exist after successful startup");
        project.getService(SlangBuiltinFiles.class).serverStarted(commands.getFirst());
    }

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
                SlangBundle.message("notification.group.slang"),
                    SlangBundle.message("server.not.found"),
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

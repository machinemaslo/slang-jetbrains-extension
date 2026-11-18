package slanglsp.server;

import slanglsp.SlangBundle;
import slanglsp.discovery.ExecutableLocator;
import slanglsp.discovery.VcpkgDiscoveryService;
import slanglsp.settings.ProjectSettings;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.EnvironmentUtil;
import com.redhat.devtools.lsp4ij.server.ProcessStreamConnectionProvider;
import java.util.List;
import java.util.Objects;
import java.nio.file.Path;

class ServerConnection extends ProcessStreamConnectionProvider {
    final Project project;
    @Override
    public void start() throws com.redhat.devtools.lsp4ij.server.CannotStartProcessException {
        if (getCommands() == null || getCommands().isEmpty()) {
            throw new com.redhat.devtools.lsp4ij.server.CannotStartProcessException(SlangBundle.message("server.not.found"));
        }
        super.start();
        // The superclass rejects null or empty commands before starting the process.
        var commands = Objects.requireNonNull(getCommands(), "Commands must exist after successful startup");
        project.getService(BuiltinFiles.class).serverStarted(commands.getFirst());
    }

    ServerConnection(Project project) {
        this.project = project;

        var exePath = findExecutable(project);
        if (exePath.isPresent()) {
            super.setCommands(List.of(exePath.get()));
            super.setWorkingDirectory(project.getBasePath());
            var recovery = project.getServiceIfCreated(ServerRecovery.class);
            if (recovery != null) {
                recovery.executableFound();
            }
            notifyDiscovered(exePath.get());
        } else {
            project.getService(ServerRecovery.class).missingExecutable();
        }
    }

    static java.util.Optional<String> findExecutable(Project project) {
        var config = ProjectSettings.getInstance(project).getState();
        return ExecutableLocator.find(config.explicitSlangdLocation, config.useLocalVcpkgSlangd,
                () -> project.getService(VcpkgDiscoveryService.class).findExecutable(),
                EnvironmentUtil.getValue("PATH"), EnvironmentUtil.getValue("VULKAN_SDK"), getLspExeName(),
                project.getBasePath());
    }

    private void notifyDiscovered(String executable) {
        String path = Path.of(executable).toAbsolutePath().normalize().toString();
        // Project workspace storage survives IDE restarts without sharing machine paths in settings.
        PropertiesComponent properties = PropertiesComponent.getInstance(project);
        synchronized (properties) {
            String key = "slanglsp.lastNotifiedSlangdPath";

            if (project.isDisposed() || path.equals(properties.getValue(key)))
                return;
            NotificationGroupManager.getInstance()
                    .getNotificationGroup("Slang LSP")
                    .createNotification(SlangBundle.message("notification.group.slang"),
                            SlangBundle.message("server.discovered", StringUtil.escapeXmlEntities(path)),
                            NotificationType.INFORMATION)
                    .notify(project);
            properties.setValue(key, path);
        }
    }

    static String getLspExeName() {
        if (SystemInfo.isWindows)
            return "slangd.exe";
        return "slangd";
    }
}

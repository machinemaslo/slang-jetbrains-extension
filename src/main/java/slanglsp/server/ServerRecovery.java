package slanglsp.server;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationActivationListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.util.Alarm;
import com.redhat.devtools.lsp4ij.LanguageServerManager;
import com.redhat.devtools.lsp4ij.LanguageServersRegistry;
import org.jetbrains.annotations.NotNull;
import slanglsp.SlangBundle;
import slanglsp.discovery.VcpkgDiscoveryService;

/** Retries only after startup failed to locate slangd, without polling. */
@Service(Service.Level.PROJECT)
public final class ServerRecovery implements Disposable {
    private final Project project;
    private final Alarm checks = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, this);
    private volatile boolean missing;
    private Notification notification;

    public ServerRecovery(Project project) {
        this.project = project;
        // Keep the VFS listener active even when vcpkg lookup is disabled.
        project.getService(VcpkgDiscoveryService.class);
        ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(
                ApplicationActivationListener.TOPIC, new ApplicationActivationListener() {
                    @Override
                    public void applicationActivated(@NotNull IdeFrame frame) {
                        retry();
                    }
                });
    }

    synchronized void missingExecutable() {
        missing = true;
        if (notification == null || notification.isExpired()) {
            notification = NotificationGroupManager.getInstance().getNotificationGroup("Slang LSP")
                    .createNotification(SlangBundle.message("notification.group.slang"),
                            SlangBundle.message("server.not.found"), NotificationType.ERROR)
                    .addAction(NotificationAction.createSimple(SlangBundle.message("server.retry"), this::retry));
            notification.notify(project);
        }
    }

    synchronized void executableFound() {
        missing = false;
        if (notification != null) {
            notification.expire();
            notification = null;
        }
    }

    public static void retryIfMissing(Project project) {
        var recovery = project.getServiceIfCreated(ServerRecovery.class);
        if (recovery != null) {
            recovery.retry();
        }
    }

    synchronized void retry() {
        if (missing && !project.isDisposed() && !checks.isDisposed()) {
            checks.cancelAllRequests();
            checks.addRequest(this::check, 200);
        }
    }

    private void check() {
        if (!missing || project.isDisposed()) {
            return;
        }
        project.getService(VcpkgDiscoveryService.class).invalidate();
        if (ServerConnection.findExecutable(project).isEmpty()) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!missing || project.isDisposed()) {
                return;
            }
            var server = LanguageServersRegistry.getInstance().getServerDefinition(BatchConnection.SERVER_ID);
            if (server == null || !server.isEnabled(project)) {
                return;
            }
            var manager = LanguageServerManager.getInstance(project);
            var status = manager.getServerStatus(BatchConnection.SERVER_ID);
            if (status == null) {
                return;
            }
            switch (status) {
                case none, stopped, not_installed -> {
                    missing = false;
                    manager.start(BatchConnection.SERVER_ID, new LanguageServerManager.StartOptions()
                            .setForceStart(true).setForceRestart(false).setWillEnable(false));
                }
                default -> {
                    // Do not interrupt a server that is running or changing state.
                }
            }
        }, project.getDisposed());
    }

    @Override
    public void dispose() {
        executableFound();
    }
}

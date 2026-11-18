package slanglsp.server;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationsManager;
import com.intellij.openapi.application.ApplicationActivationListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.ui.BalloonLayout;
import com.redhat.devtools.lsp4ij.LanguageServerManager;
import com.redhat.devtools.lsp4ij.LanguageServersRegistry;
import com.redhat.devtools.lsp4ij.ServerStatus;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import slanglsp.SlangBundle;
import slanglsp.TestDependencies;
import slanglsp.settings.ProjectSettings;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Rectangle;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ServerRecoveryTest {
    enum Trigger {
        ACTIVATION, VFS, RETRY_ACTION
    }

    @ParameterizedTest
    @EnumSource(Trigger.class)
    void startsAfterMissingExecutableWithoutRestartingTheIde(Trigger trigger, @TempDir Path temporary) throws Exception {
        var fixture = IdeaTestFixtureFactory.getFixtureFactory()
                .createLightFixtureBuilder(new LightProjectDescriptor(), "ServerRecovery").getFixture();
        EdtTestUtil.runInEdtAndWait(fixture::setUp);
        try {
            var project = fixture.getProject();
            var definition = LanguageServersRegistry.getInstance().getServerDefinition(BatchConnection.SERVER_ID);
            assertNotNull(definition);
            var settings = ProjectSettings.getInstance(project);
            var original = settings.getState();
            var state = settings.getState();
            state.useLocalVcpkgSlangd = false;
            state.explicitSlangdLocation = "";
            settings.loadState(state);
            try {
                assumeTrue(ServerConnection.findExecutable(project).isEmpty(), "Requires no unrelated slangd on PATH or in Vulkan SDK");
                var manager = LanguageServerManager.getInstance(project);
                EdtTestUtil.runInEdtAndWait(() -> manager.start(BatchConnection.SERVER_ID,
                        new LanguageServerManager.StartOptions().setForceStart(true).setForceRestart(false)));
                EdtTestUtil.runInEdtAndWait(() -> {
                    PlatformTestUtil.waitWithEventsDispatching("missing slangd startup stops",
                            () -> manager.getServerStatus(BatchConnection.SERVER_ID) == ServerStatus.stopped
                                    && project.getServiceIfCreated(ServerRecovery.class) != null, 15);
                    assertTrue(definition.isEnabled(project), "Missing slangd must not disable the server");
                });

                state.explicitSlangdLocation = Path.of(TestDependencies.slangd()).getParent().toString();
                settings.loadState(state);
                EdtTestUtil.runInEdtAndWait(() -> {
                    switch (trigger) {
                        case ACTIVATION -> ApplicationManager.getApplication().getMessageBus()
                                .syncPublisher(ApplicationActivationListener.TOPIC)
                                .applicationActivated(new TestFrame(project));
                        case VFS -> {
                            var directory = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(temporary);
                            assertNotNull(directory);
                            WriteAction.run(() -> directory.createChildData(this, ServerConnection.getLspExeName()));
                        }
                        case RETRY_ACTION -> {
                            var notice = Arrays.stream(NotificationsManager.getNotificationsManager()
                                            .getNotificationsOfType(Notification.class, project))
                                    .filter(item -> item.getActions().stream().anyMatch(action ->
                                            SlangBundle.message("server.retry").equals(action.getTemplateText())))
                                    .findFirst().orElseThrow();
                            Notification.fire(notice, notice.getActions().stream()
                                    .filter(NotificationAction.class::isInstance).findFirst().orElseThrow(),
                                    com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
                                            .add(Notification.KEY, notice).build());
                        }
                    }
                    PlatformTestUtil.waitWithEventsDispatching("slangd recovers after " + trigger,
                            () -> manager.getServerStatus(BatchConnection.SERVER_ID) == ServerStatus.started, 15);
                });
            } finally {
                EdtTestUtil.runInEdtAndWait(() -> LanguageServerManager.getInstance(project).stop(BatchConnection.SERVER_ID));
                settings.loadState(original);
            }
        } finally {
            EdtTestUtil.runInEdtAndWait(fixture::tearDown);
        }
    }

    private static final class TestFrame implements IdeFrame {
        private final Project project;
        private final JPanel component = new JPanel();

        private TestFrame(Project project) {
            this.project = project;
        }

        @Override
        public @NotNull Project getProject() {
            return project;
        }

        @Override
        public @NotNull JComponent getComponent() {
            return component;
        }

        @Override
        public @NotNull Rectangle suggestChildFrameBounds() {
            return new Rectangle();
        }

        @Override
        public StatusBar getStatusBar() {
            return null;
        }

        @Override
        public BalloonLayout getBalloonLayout() {
            return null;
        }

        @Override
        public void setFrameTitle(String title) {
            component.setName(title);
        }
    }
}

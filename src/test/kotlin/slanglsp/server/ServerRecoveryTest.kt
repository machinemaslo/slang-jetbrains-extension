package slanglsp.server

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationsManager
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.ui.BalloonLayout
import java.awt.Rectangle
import java.io.IOException
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JPanel
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import slanglsp.SlangBundle.message
import slanglsp.TestDependencies
import slanglsp.discovery.ExecutableLocator.slangdName
import slanglsp.editor.NativeServerSupport
import slanglsp.server.ServerConnection.findExecutable
import slanglsp.server.nativeclient.runningSlangClient
import slanglsp.settings.project.ProjectSettings

internal class ServerRecoveryTest {
    internal enum class Trigger {
        ACTIVATION,
        VFS,
        RETRY_ACTION
    }

    @ParameterizedTest
    @EnumSource(Trigger::class)
    @Throws(Exception::class)
    fun startsAfterMissingExecutableWithoutRestartingTheIde(trigger: Trigger, @TempDir temporary: Path) {
        val fixture = NativeServerSupport.isolatedFixture("ServerRecovery")
        runInEdtAndWait { fixture.setUp() }
        try {
            val project = fixture.project
            val settings = ProjectSettings.getInstance(project)
            val original = settings.getState()
            val state = settings.getState()
            state.useLocalVcpkgSlangd = false
            state.explicitSlangdLocation = ""
            settings.loadState(state)
            try {
                Assumptions.assumeTrue(
                    findExecutable(project) == null, "Requires no unrelated slangd on PATH or in Vulkan SDK")
                runInEdtAndWait {
                    TrustedProjects.setProjectTrusted(project, true)
                    ModuleRootModificationUtil.addContentRoot(fixture.module, fixture.tempDirPath)
                    val file = fixture.addFileToProject("recover.slang", "float value = 0;\n")
                    FileEditorManager.getInstance(project).openFile(file.virtualFile, true)
                }
                runInEdtAndWait {
                    PlatformTestUtil.waitWithEventsDispatching(
                        "missing slangd startup stops",
                        {
                            project.getServiceIfCreated(ServerRecovery::class.java) != null &&
                                NotificationsManager.getNotificationsManager()
                                    .getNotificationsOfType(Notification::class.java, project)
                                    .any { notice -> notice.actions.any { it.templateText == message("server.retry") } }
                        },
                        15)
                    Assertions.assertNull(runningSlangClient(project))
                }

                state.explicitSlangdLocation = Path.of(TestDependencies.slangd()).parent.toString()
                settings.loadState(state)
                runInEdtAndWait {
                    when (trigger) {
                        // Other platform subscribers expect a real ProjectFrameHelper, not this fixture frame.
                        Trigger.ACTIVATION ->
                            project.getService(ServerRecovery::class.java).applicationActivated(TestFrame(project))

                        Trigger.VFS -> {
                            val directory =
                                checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(temporary)) {
                                    "Test directory not found in VFS: $temporary"
                                }
                            WriteAction.run<IOException?> {
                                directory.createChildData(this, slangdName)
                            }
                        }

                        Trigger.RETRY_ACTION -> {
                            val retryText = message("server.retry")
                            val (notice, retry) =
                                NotificationsManager.getNotificationsManager()
                                    .getNotificationsOfType(Notification::class.java, project)
                                    .firstNotNullOfOrNull { notice ->
                                        notice.actions
                                            .filterIsInstance<NotificationAction>()
                                            .firstOrNull { it.templateText == retryText }
                                            ?.let { notice to it }
                                    } ?: throw AssertionError("Missing Slang notification with the '$retryText' action")

                            val context =
                                SimpleDataContext.builder()
                                    .add(Notification.KEY, notice)
                                    .add(CommonDataKeys.PROJECT, project)
                                    .build()
                            val event =
                                AnActionEvent.createEvent(
                                    retry, context, null, ActionPlaces.NOTIFICATION, ActionUiKind.NONE, null)
                            retry.actionPerformed(event)
                        }
                    }
                    PlatformTestUtil.waitWithEventsDispatching(
                        "slangd recovers after $trigger",
                        { runningSlangClient(project) != null },
                        15)
                }
            } finally {
                NativeServerSupport.stop(project)
                settings.loadState(original)
            }
        } finally {
            runInEdtAndWait { fixture.tearDown() }
        }
    }

    private class TestFrame(private val project: Project) : IdeFrame {
        private val component = JPanel()

        override fun getProject(): Project {
            return project
        }

        override fun getComponent(): JComponent {
            return component
        }

        override fun suggestChildFrameBounds(): Rectangle {
            return Rectangle()
        }

        override fun getStatusBar(): StatusBar? {
            return null
        }

        override fun getBalloonLayout(): BalloonLayout? {
            return null
        }

        override fun setFrameTitle(title: String?) {
            component.setName(title)
        }
    }
}

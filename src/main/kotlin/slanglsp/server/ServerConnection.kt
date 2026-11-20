package slanglsp.server

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import java.nio.file.Path
import slanglsp.SlangBundle
import slanglsp.discovery.ExecutableLocator
import slanglsp.settings.project.ProjectSettings

internal object ServerConnection {
    private const val LAST_NOTIFIED_SLANGD_PATH = "slanglsp.lastNotifiedSlangdPath"

    fun findEditorExecutable(project: Project): String? {
        val exePath = findExecutable(project)
        if (exePath != null) {
            project.getServiceIfCreated(ServerRecovery::class.java)?.executableFound()
            notifyDiscovered(project, exePath)
        } else {
            project.getService(ServerRecovery::class.java).missingExecutable()
        }
        return exePath
    }

    private fun notifyDiscovered(project: Project, executable: String) {
        val path = Path.of(executable).toAbsolutePath().normalize().toString()
        // Project workspace storage survives IDE restarts without sharing machine paths in settings.
        val properties = PropertiesComponent.getInstance(project)
        synchronized(properties) {
            if (project.isDisposed || path == properties.getValue(LAST_NOTIFIED_SLANGD_PATH)) {
                return
            }
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Slang LSP")
                .createNotification(
                    SlangBundle.message("notification.group.slang"),
                    SlangBundle.message("server.discovered", StringUtil.escapeXmlEntities(path)),
                    NotificationType.INFORMATION)
                .notify(project)
            properties.setValue(LAST_NOTIFIED_SLANGD_PATH, path)
        }
    }

    fun findExecutable(project: Project): String? =
        ExecutableLocator.findSlangd(project, ProjectSettings.getInstance(project).getState())
}

package slanglsp.server

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.redhat.devtools.lsp4ij.server.CannotStartProcessException
import com.redhat.devtools.lsp4ij.server.ProcessStreamConnectionProvider
import java.nio.file.Path
import slanglsp.SlangBundle
import slanglsp.discovery.ExecutableLocator
import slanglsp.settings.project.ProjectSettings

internal class ServerConnection(val project: Project) : ProcessStreamConnectionProvider() {
    @Throws(CannotStartProcessException::class)
    override fun start() {
        if (commands.isNullOrEmpty()) {
            throw CannotStartProcessException(SlangBundle.message("server.not.found"))
        }
        super.start()
        // The superclass rejects null or empty commands before starting the process.
        val commands = checkNotNull(commands) { "Commands must exist after successful startup" }
        project.getService<BuiltinFiles?>(BuiltinFiles::class.java)!!.serverStarted(commands.first())
    }

    init {
        val exePath = findExecutable(project)
        if (exePath != null) {
            commands = listOf(exePath)
            workingDirectory = project.basePath
            project.getServiceIfCreated(ServerRecovery::class.java)?.executableFound()
            notifyDiscovered(exePath)
        } else {
            project.getService<ServerRecovery?>(ServerRecovery::class.java)!!.missingExecutable()
        }
    }

    private fun notifyDiscovered(executable: String) {
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

    companion object {
        private const val LAST_NOTIFIED_SLANGD_PATH = "slanglsp.lastNotifiedSlangdPath"

        fun findExecutable(project: Project): String? =
            ExecutableLocator.findSlangd(project, ProjectSettings.getInstance(project).getState())
    }
}

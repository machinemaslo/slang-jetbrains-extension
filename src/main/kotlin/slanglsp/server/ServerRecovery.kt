package slanglsp.server

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFrame
import com.intellij.util.Alarm
import kotlin.concurrent.Volatile
import slanglsp.SlangBundle
import slanglsp.discovery.VcpkgDiscoveryService
import slanglsp.server.nativeclient.restartSlangClient

/** Retries only after startup failed to locate slangd, without polling. */
@Service(Service.Level.PROJECT)
class ServerRecovery(private val project: Project) : Disposable, ApplicationActivationListener {
    private val checks = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    @Volatile private var missing = false
    private var notification: Notification? = null

    init {
        // Keep the VFS listener active even when vcpkg lookup is disabled.
        project.getService<VcpkgDiscoveryService?>(VcpkgDiscoveryService::class.java)
        ApplicationManager.getApplication()
            .messageBus
            .connect(this)
            .subscribe(ApplicationActivationListener.TOPIC, this)
    }

    override fun applicationActivated(frame: IdeFrame) {
        retry()
    }

    @Synchronized
    fun missingExecutable() {
        missing = true
        if (notification == null || notification!!.isExpired) {
            notification =
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("Slang LSP")
                    .createNotification(
                        SlangBundle.message("notification.group.slang"),
                        SlangBundle.message("server.not.found"),
                        NotificationType.ERROR)
                    .addAction(NotificationAction.createSimple(SlangBundle.message("server.retry")) { this.retry() })
            notification!!.notify(project)
        }
    }

    @Synchronized
    fun executableFound() {
        missing = false
        if (notification != null) {
            notification!!.expire()
            notification = null
        }
    }

    @Synchronized
    fun retry() {
        if (missing && !project.isDisposed && !checks.isDisposed) {
            checks.cancelAllRequests()
            checks.addRequest({ this.check() }, 200)
        }
    }

    private fun check() {
        if (!missing || project.isDisposed) {
            return
        }
        project.getService<VcpkgDiscoveryService?>(VcpkgDiscoveryService::class.java)!!.invalidate()
        if (ServerConnection.findExecutable(project) == null) {
            return
        }
        ApplicationManager.getApplication()
            .invokeLater(
                Runnable {
                    if (!missing || project.isDisposed) {
                        return@Runnable
                    }
                    executableFound()
                    restartSlangClient(project)
                },
                project.disposed)
    }

    override fun dispose() {
        executableFound()
    }

    companion object {
        fun retryIfMissing(project: Project) {
            val recovery = project.getServiceIfCreated<ServerRecovery?>(ServerRecovery::class.java)
            recovery?.retry()
        }
    }
}

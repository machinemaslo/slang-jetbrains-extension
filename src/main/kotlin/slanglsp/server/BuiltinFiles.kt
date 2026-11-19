package slanglsp.server

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import slanglsp.SlangBundle
import slanglsp.language.SlangFileType

/** Read-only, unindexed source from the exact executable used by the running server. */
@Service(Service.Level.PROJECT)
class BuiltinFiles(private val project: Project) : Disposable {
    private val modules: MutableMap<String?, Load?> = HashMap()
    private var executable: String? = null

    @Synchronized
    fun serverStarted(executable: String?) {
        clear()
        this.executable = executable
    }

    @Synchronized
    fun clear() {
        for (load in modules.values) {
            load!!.indicator.cancel()
            load.result.cancel(false)
        }
        modules.clear()
        executable = null
    }

    fun resolve(uri: String): BuiltinFile? {
        val module: String = moduleName(uri) ?: return null

        val load: Load
        synchronized(this) {
            if (executable == null || project.isDisposed) {
                return null
            }
            load =
                modules.computeIfAbsent(module) { name ->
                    Load(executable, checkNotNull(name))
                }!!
            load.waiters++
        }

        try {
            return ProgressIndicatorUtils.awaitWithCheckCanceled(load.result)
        } catch (_: CancellationException) {
            throw ProcessCanceledException()
        } finally {
            synchronized(this) {
                if (--load.waiters == 0 && !load.result.isDone) {
                    load.indicator.cancel()
                    load.result.cancel(false)
                    modules.remove(module, load)
                }
            }
        }
    }

    private inner class Load(executable: String?, module: String) {
        val indicator: ProgressIndicatorBase = ProgressIndicatorBase()
        val result: CompletableFuture<BuiltinFile?> = CompletableFuture()
        var waiters: Int = 0

        init {
            AppExecutorUtil.getAppExecutorService().execute {
                try {
                    indicator.checkCanceled()
                    val command =
                        GeneralCommandLine(executable, "--print-builtin-module", module)
                            .withCharset(StandardCharsets.UTF_8)
                            .withWorkDirectory(project.basePath)
                    val output = CapturingProcessHandler(command).runProcessWithProgressIndicator(indicator, 15000)
                    indicator.checkCanceled()

                    check(!(output.isTimeout || output.getExitCode() != 0 || output.stdout.isEmpty())) {
                        if (output.isTimeout) {
                            SlangBundle.message("builtin.timeout")
                        } else {
                            SlangBundle.message("builtin.print.failed", module, output.stderr)
                        }
                    }

                    result.complete(BuiltinFile(module, output.stdout))
                } catch (canceled: ProcessCanceledException) {
                    result.cancel(false)
                    throw canceled
                } catch (failure: Exception) {
                    // Cache a failed attempt until restart, avoiding repeated hover notifications.
                    result.complete(null)

                    if (!project.isDisposed && !indicator.isCanceled) {
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("Slang LSP")
                            .createNotification(
                                SlangBundle.message("builtin.open.failed"), failure.message!!, NotificationType.WARNING)
                            .notify(project)
                    }
                }
            }
        }
    }

    class BuiltinFile internal constructor(module: String, text: String) :
        LightVirtualFile("$module.builtin", SlangFileType, text) {
        private val uri: URI = URI.create("slang-synth://$module/$module.builtin")

        init {
            isWritable = false
        }

        fun uri(): URI {
            return uri
        }
    }

    override fun dispose() {
        clear()
    }

    companion object {
        fun moduleName(value: String): String? {
            try {
                val uri = URI.create(value)
                val module = uri.getAuthority()
                return if ("slang-synth" == uri.scheme &&
                    ("core" == module || "glsl" == module) &&
                    ("/$module.builtin") == uri.getPath() &&
                    uri.getQuery() == null &&
                    uri.getFragment() == null)
                    module
                else null
            } catch (_: IllegalArgumentException) {
                return null
            }
        }
    }
}

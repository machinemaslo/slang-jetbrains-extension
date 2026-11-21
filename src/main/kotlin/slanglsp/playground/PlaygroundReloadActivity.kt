package slanglsp.playground

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.AppIcon
import java.awt.Frame
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Only enabled by the CLion playground launcher, for its exact staged project. */
internal class PlaygroundReloadActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val playground = System.getProperty("slang.playground.project") ?: return
        val launchId = parseLaunchId(System.getProperty("slang.playground.launch.id")) ?: return
        if (ApplicationInfo.getInstance().build.productCode != "CL" ||
            project.basePath?.let { Path.of(it).toAbsolutePath().normalize() } != Path.of(playground)) {
            return
        }
        project.service<PlaygroundReload>().start(Path.of(playground), launchId)
    }
}

@Service(Service.Level.PROJECT)
internal class PlaygroundReload(private val project: Project, private val scope: CoroutineScope) {
    fun start(playground: Path, startup: UUID) {
        val request = PathManager.getSystemDir().resolve("slang-playground.reload")
        scope.launch(Dispatchers.IO) {
            while (isActive && !project.isDisposed) {
                delay(500.milliseconds)
                if (!Files.isRegularFile(request)) {
                    continue
                }
                val id = takeRequest(request) ?: continue
                if (id == startup) {
                    continue
                }
                withContext(Dispatchers.EDT) {
                    if (!project.isDisposed) {
                        val windows = WindowManager.getInstance()
                        windows.getFrame(project)?.let { frame ->
                            val restored = frame.extendedState and Frame.ICONIFIED.inv()
                            if (restored != frame.extendedState) {
                                frame.extendedState = restored
                            }
                            frame.toFront()
                        }
                        windows.getIdeFrame(project)?.let { AppIcon.getInstance().requestFocus(it) }
                    }
                }
                while (isActive && !project.isDisposed) {
                    val reloading = withContext(Dispatchers.EDT) {
                        if (project.isDisposed || hasUnsavedFiles(playground)) {
                            false
                        } else {
                            log.info("Reloading the CLion playground after a launcher request")
                            ProjectManager.getInstance().reloadProject(project)
                            true
                        }
                    }
                    if (reloading) {
                        break
                    }
                    delay(500.milliseconds)
                }
            }
        }
    }

    private fun hasUnsavedFiles(playground: Path): Boolean {
        val manager = FileDocumentManager.getInstance()
        return manager.unsavedDocuments.any { document ->
            val file = manager.getFile(document)
            file != null && file.isInLocalFileSystem && file.toNioPath().toAbsolutePath().normalize().startsWith(playground)
        }
    }

    private fun takeRequest(request: Path): UUID? {
        val received = request.resolveSibling("slang-playground.reload.received")
        return try {
            try {
                try {
                    Files.move(request, received, ATOMIC_MOVE, REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(request, received, REPLACE_EXISTING)
                }
            } catch (_: NoSuchFileException) {
                return null
            }
            try {
                val bytes = Files.newInputStream(received).use { it.readNBytes(37) }
                parseLaunchId(bytes.decodeToString())
            } finally {
                Files.deleteIfExists(received)
            }
        } catch (failure: IOException) {
            log.debug("Could not read the playground reload request", failure)
            null
        }
    }

    private companion object {
        val log = Logger.getInstance(PlaygroundReload::class.java)
    }
}

private fun parseLaunchId(value: String?): UUID? {
    if (value?.length != 36) {
        return null
    }
    return try {
        UUID.fromString(value)
    } catch (_: IllegalArgumentException) {
        null
    }
}

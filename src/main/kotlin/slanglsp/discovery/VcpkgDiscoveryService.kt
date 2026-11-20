package slanglsp.discovery

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.EnvironmentUtil
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.minutes
import slanglsp.discovery.ExecutableLocator.isExecutable
import slanglsp.server.ServerRecovery

@Service(Service.Level.PROJECT)
class VcpkgDiscoveryService(project: Project) : Disposable {
    private val cache = DiscoveryCache(
        discover = {
            project.basePath?.let { base ->
                VcpkgDiscovery(ExecutableLocator.slangdName, SystemInfo.OS_NAME, SystemInfo.OS_ARCH)
                    .find(Path(base), EnvironmentUtil.getEnvironmentMap())
            }
        },
        lifetime = 1.minutes,
    )

    init {
        // The fallback expiry also handles excluded/external build directories not refreshed by VFS.
        ApplicationManager.getApplication()
            .messageBus
            .connect(this)
            .subscribe(
                VirtualFileManager.VFS_CHANGES,
                object : BulkFileListener {
                    override fun after(events: MutableList<out VFileEvent>) {
                        val projectPrefix = project.basePath?.replace('\\', '/')?.plus("/")

                        if (events.any { affectsDiscovery(it.path.replace('\\', '/'), projectPrefix) }) {
                            cache.invalidate()
                            ServerRecovery.retryIfMissing(project)
                        }
                    }
                },
            )
    }

    fun findExecutable(): String? {
        val found = cache.get()

        if (found != null && !isExecutable(Path(found))) {
            cache.invalidate()

            return cache.get()
        }

        return found
    }

    fun invalidate() {
        cache.invalidate()
    }

    override fun dispose() {
        // The message bus disconnects the listener when this service is disposed.
    }

    private companion object {
        // External caches/imports can also supply the selected installation root.
        val metadataSuffixes = listOf(
            "/CMakeCache.txt",
            ".props",
            ".targets",
            "/compile_commands.json",
            "/xmake.conf",
            "/intro-dependencies.json",
        )

        val projectSuffixes = listOf(
            ".json",
            ".slnx",
            ".sln",
            ".vcxproj",
            ".csproj",
            ".mk",
            "/Makefile",
            "/makefile",
            "/GNUmakefile",
            "/workspace.xml",
            "/cmake.xml",
            "/.gitmodules",
            "/xmake.lua",
            "/meson.build",
            "/vcpkg_installed",
            "/installed",
        )

        val executableSuffixes = listOf("/slangd", "/slangd.exe", "/slangc", "/slangc.exe")
        val toolDirectories = listOf("/tools/shader-slang", "/tools/slang")

        fun affectsDiscovery(path: String, projectPrefix: String?): Boolean {
            val inProject = projectPrefix != null && path.startsWith(projectPrefix)

            return metadataSuffixes.any { path.endsWith(it) } ||
                (inProject && projectSuffixes.any { path.endsWith(it) }) ||
                executableSuffixes.any { path.endsWith(it) } ||
                toolDirectories.any { path.endsWith(it) || "$it/" in path }
        }
    }
}

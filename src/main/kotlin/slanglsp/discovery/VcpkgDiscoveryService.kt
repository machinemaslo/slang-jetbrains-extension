package slanglsp.discovery

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.EnvironmentUtil
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import slanglsp.discovery.ExecutableLocator.isExecutable
import slanglsp.server.ServerRecovery

@Service(Service.Level.PROJECT)
class VcpkgDiscoveryService(project: Project) : Disposable {
    private val cache: DiscoveryCache =
        DiscoveryCache(
            discover = {
                project.basePath?.let { base ->
                    VcpkgDiscovery(
                            ExecutableLocator.slangdName, System.getProperty("os.name"), System.getProperty("os.arch"))
                        .find(Path.of(base), EnvironmentUtil.getEnvironmentMap())
                }
            },
            clock = System::nanoTime,
            lifetime = TimeUnit.MINUTES.toNanos(1))

    init {
        // The fallback expiry also handles excluded/external build directories not refreshed by VFS.
        ApplicationManager.getApplication()
            .messageBus
            .connect(this)
            .subscribe(
                VirtualFileManager.VFS_CHANGES,
                object : BulkFileListener {
                    override fun after(events: MutableList<out VFileEvent>) {
                        for (event in events) {
                            val path = event.getPath().replace('\\', '/')
                            val base = project.basePath
                            val inProject = base != null && path.startsWith(base.replace('\\', '/') + "/")
                            // External caches/imports can also supply the selected installation root.
                            if (path.endsWith("/CMakeCache.txt") ||
                                path.endsWith(".props") ||
                                path.endsWith(".targets") ||
                                path.endsWith("/compile_commands.json") ||
                                path.endsWith("/xmake.conf") ||
                                path.endsWith("/intro-dependencies.json") ||
                                (inProject &&
                                    (path.endsWith(".json") ||
                                        path.endsWith(".slnx") ||
                                        path.endsWith(".sln") ||
                                        path.endsWith(".vcxproj") ||
                                        path.endsWith(".csproj") ||
                                        path.endsWith(".mk") ||
                                        path.endsWith("/Makefile") ||
                                        path.endsWith("/makefile") ||
                                        path.endsWith("/GNUmakefile") ||
                                        path.endsWith("/workspace.xml") ||
                                        path.endsWith("/cmake.xml") ||
                                        path.endsWith("/.gitmodules") ||
                                        path.endsWith("/xmake.lua") ||
                                        path.endsWith("/meson.build") ||
                                        path.endsWith("/vcpkg_installed") ||
                                        path.endsWith("/installed"))) ||
                                path.endsWith("/slangd") ||
                                path.endsWith("/slangd.exe") ||
                                path.endsWith("/slangc") ||
                                path.endsWith("/slangc.exe") ||
                                path.endsWith("/tools/shader-slang") ||
                                path.endsWith("/tools/slang") ||
                                path.contains("/tools/shader-slang/") ||
                                path.contains("/tools/slang/")) {
                                cache.invalidate()
                                ServerRecovery.retryIfMissing(project)
                                break
                            }
                        }
                    }
                })
    }

    fun findExecutable(): String? {
        val found = cache.get()

        if (found != null && !isExecutable(Path.of(found))) {
            cache.invalidate()

            return cache.get()
        }

        return found
    }

    fun invalidate() {
        cache.invalidate()
    }

    override fun dispose() {}
}

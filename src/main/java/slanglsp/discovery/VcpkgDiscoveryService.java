package slanglsp.discovery;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.util.EnvironmentUtil;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service(Service.Level.PROJECT)
public final class VcpkgDiscoveryService implements Disposable {
    private final DiscoveryCache cache;

    public VcpkgDiscoveryService(Project project) {
        cache = new DiscoveryCache(() -> {
            String base = project.getBasePath();

            return base == null ? Optional.empty()
                                : new VcpkgDiscovery(SystemInfo.isWindows ? "slangd.exe" : "slangd",
                                          System.getProperty("os.name"), System.getProperty("os.arch"))
                                          .find(Path.of(base), EnvironmentUtil.getEnvironmentMap());
        }, System::nanoTime, TimeUnit.MINUTES.toNanos(1));
        // The fallback expiry also handles excluded/external build directories not refreshed by VFS.
        ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(
                VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override
            public void after(@NotNull List<? extends VFileEvent> events) {
                for (VFileEvent event : events) {
                    String path = event.getPath().replace('\\', '/');
                    String base = project.getBasePath();
                    boolean inProject = base != null && path.startsWith(base.replace('\\', '/') + "/");
                    // External caches/imports can also supply the selected installation root.
                    if (path.endsWith("/CMakeCache.txt") || path.endsWith(".props") || path.endsWith(".targets") ||
                            path.endsWith("/compile_commands.json") || path.endsWith("/xmake.conf") ||
                            path.endsWith("/intro-dependencies.json") ||
                            (inProject && (path.endsWith(".json") || path.endsWith(".slnx") || path.endsWith(".sln") ||
                                                  path.endsWith(".vcxproj") || path.endsWith(".csproj") ||
                                                  path.endsWith(".mk") || path.endsWith("/Makefile") ||
                                                  path.endsWith("/makefile") || path.endsWith("/GNUmakefile") ||
                                                  path.endsWith("/workspace.xml") || path.endsWith("/cmake.xml") ||
                                                  path.endsWith("/.gitmodules") || path.endsWith("/xmake.lua") ||
                                                  path.endsWith("/meson.build") || path.endsWith("/vcpkg_installed") ||
                                                  path.endsWith("/installed"))) ||
                            path.endsWith("/slangd") || path.endsWith("/slangd.exe") ||
                            path.contains("/tools/shader-slang/") || path.contains("/tools/slang/")) {
                        cache.invalidate();
                        break;
                    }
                }
            }
                });
    }

    public Optional<String> findExecutable() {
        Optional<String> found = cache.get();

        if (found.isPresent() && !ExecutableLocator.isExecutable(Path.of(found.get()))) {
            cache.invalidate();

            return cache.get();
        }

        return found;
    }

    public void invalidate() {
        cache.invalidate();
    }

    @Override
    public void dispose() {
    }
}

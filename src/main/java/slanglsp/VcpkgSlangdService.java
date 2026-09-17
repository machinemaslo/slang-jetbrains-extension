package slanglsp;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service(Service.Level.PROJECT)
public final class VcpkgSlangdService implements Disposable {
    private final SlangDiscoveryCache cache;

    public VcpkgSlangdService(Project project) {
        cache = new SlangDiscoveryCache(() -> {
            String base = project.getBasePath();
            return base == null ? Optional.empty() : new VcpkgSlangdDiscovery(
                    SystemInfo.isWindows ? "slangd.exe" : "slangd", System.getProperty("os.name"),
                    System.getProperty("os.arch")).find(Path.of(base));
        }, System::nanoTime, TimeUnit.MINUTES.toNanos(1));
        // The fallback expiry also handles excluded/external build directories not refreshed by VFS.
        ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override
            public void after(@NotNull List<? extends VFileEvent> events) {
                for (VFileEvent event : events) {
                    String path = event.getPath().replace('\\', '/');
                    String base = project.getBasePath();
                    boolean inProject = base != null && path.startsWith(base.replace('\\', '/') + "/");
                    if ((inProject && (path.endsWith("/vcpkg.json") || path.endsWith("/CMakeCache.txt")
                            || path.endsWith("/vcpkg_installed") || path.endsWith("/installed")))
                            || path.endsWith("/slangd") || path.endsWith("/slangd.exe")
                            || path.contains("/tools/shader-slang/") || path.contains("/tools/slang/")) {
                        cache.invalidate();
                        break;
                    }
                }
            }
        });
    }

    Optional<String> findExecutable() {
        Optional<String> found = cache.get();
        if (found.isPresent() && !SlangExecutableLocator.isExecutable(Path.of(found.get()))) {
            cache.invalidate();
            return cache.get();
        }
        return found;
    }

    void invalidate() { cache.invalidate(); }

    @Override
    public void dispose() { }
}

package slanglsp;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.util.ProgressIndicatorBase;
import com.intellij.openapi.progress.util.ProgressIndicatorUtils;
import com.intellij.openapi.project.Project;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Read-only, unindexed source from the exact executable used by the running server. */
@Service(Service.Level.PROJECT)
public final class SlangBuiltinFiles implements Disposable {
    private final Project project;
    private final Map<String, Load> modules = new HashMap<>();
    private String executable;

    public SlangBuiltinFiles(Project project) { this.project = project; }

    public synchronized void serverStarted(String executable) {
        clear();
        this.executable = executable;
    }

    public synchronized void clear() {
        modules.values().forEach(load -> {
            load.indicator.cancel();
            load.result.cancel(false);
        });
        modules.clear();
        executable = null;
    }

    public BuiltinFile resolve(String uri) {
        String module = moduleName(uri);
        if (module == null) return null;
        Load load;
        synchronized (this) {
            if (executable == null || project.isDisposed()) return null;
            load = modules.computeIfAbsent(module, name -> new Load(executable, name));
            load.waiters++;
        }
        try {
            return ProgressIndicatorUtils.awaitWithCheckCanceled(load.result);
        } catch (java.util.concurrent.CancellationException canceled) {
            throw new ProcessCanceledException();
        } finally {
            synchronized (this) {
                if (--load.waiters == 0 && !load.result.isDone()) {
                    load.indicator.cancel();
                    load.result.cancel(false);
                    modules.remove(module, load);
                }
            }
        }
    }

    static String moduleName(String value) {
        try {
            URI uri = URI.create(value);
            String module = uri.getAuthority();
            return "slang-synth".equals(uri.getScheme()) && ("core".equals(module) || "glsl".equals(module))
                    && ("/" + module + ".builtin").equals(uri.getPath())
                    && uri.getQuery() == null && uri.getFragment() == null ? module : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private final class Load {
        final ProgressIndicatorBase indicator = new ProgressIndicatorBase();
        final CompletableFuture<BuiltinFile> result = new CompletableFuture<>();
        int waiters;

        Load(String executable, String module) {
            AppExecutorUtil.getAppExecutorService().execute(() -> {
                try {
                    indicator.checkCanceled();
                    var command = new GeneralCommandLine(executable, "--print-builtin-module", module)
                            .withCharset(StandardCharsets.UTF_8).withWorkDirectory(project.getBasePath());
                    var output = new CapturingProcessHandler(command).runProcessWithProgressIndicator(indicator, 15000);
                    indicator.checkCanceled();
                    if (output.isTimeout() || output.getExitCode() != 0 || output.getStdout().isEmpty()) {
                        throw new IllegalStateException(output.isTimeout() ? "slangd timed out"
                                : "slangd could not print " + module + ": " + output.getStderr());
                    }
                    result.complete(new BuiltinFile(module, output.getStdout()));
                } catch (ProcessCanceledException canceled) {
                    result.cancel(false);
                } catch (Exception failure) {
                    // Cache a failed attempt until restart, avoiding repeated hover notifications.
                    result.complete(null);
                    if (!project.isDisposed() && !indicator.isCanceled()) {
                        NotificationGroupManager.getInstance().getNotificationGroup("Slang LSP")
                                .createNotification("Cannot open Slang built-in source", failure.getMessage(), NotificationType.WARNING)
                                .notify(project);
                    }
                }
            });
        }
    }

    public static final class BuiltinFile extends LightVirtualFile {
        private final URI uri;
        BuiltinFile(String module, String text) {
            super(module + ".builtin", SlangFileType.INSTANCE, text);
            uri = URI.create("slang-synth://" + module + "/" + module + ".builtin");
            setWritable(false);
        }
        public URI uri() { return uri; }
    }

    @Override public void dispose() { clear(); }
}

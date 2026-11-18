package slanglsp.editor;

import com.intellij.codeInsight.CodeSmellInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.util.ProgressIndicatorBase;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.vcs.CodeSmellDetector;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import com.intellij.util.ui.update.MergingUpdateQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import slanglsp.FormattingFixtures;
import slanglsp.TestDependencies;
import slanglsp.settings.ProjectSettings;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real queued external-tool pass, not its synchronous unit-test shortcut. */
class CommitAnalysisSaturationTest {
    private static final int ANALYSIS_BUDGET_SECONDS = 8;

    @Test
    @Timeout(120)
    void queuedCommitChecksCompleteAboveCommonPoolParallelism() throws Exception {
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        var fixture = factory.createCodeInsightFixture(
                factory.createLightFixtureBuilder("CommitAnalysisSaturation").getFixture(),
                new TempDirTestFixtureImpl());
        EdtTestUtil.runInEdtAndWait(fixture::setUp);
        var queue = new QueuedExternalInspections();
        try {
            int parallelism = ForkJoinPool.getCommonPoolParallelism();
            var files = new ArrayList<PsiFile>();
            EdtTestUtil.runInEdtAndWait(() -> {
                ModuleRootModificationUtil.addContentRoot(fixture.getModule(), fixture.getTempDirPath());
                fixture.enableInspections(new BatchDiagnosticsInspection(), new InconsistentNamingInspection(),
                        new UnusedSymbolsInspection());
                var state = ProjectSettings.getInstance(fixture.getProject()).getState();
                state.explicitSlangdLocation = Path.of(TestDependencies.slangd()).getParent().toString();
                state.useLocalVcpkgSlangd = false;
                ProjectSettings.getInstance(fixture.getProject()).loadState(state);
                NativeServerSupport.enable(fixture.getProject());
                // Keep the actual sources from the stalled commit, including their whitespace.
                for (int i = 0; i < parallelism + 8; i++) {
                    String scenario = i % 2 == 0 ? "wrapping/before.slang" : "token-preservation/switch.slang";
                    files.add(fixture.addFileToProject("copy" + i + "/" + scenario, FormattingFixtures.text(scenario)));
                }
                files.add(fixture.addFileToProject("naming.slang",
                        "float BadName() { float unusedLocal; return 0; }\n"));
                files.add(fixture.addFileToProject("diagnostics.slang",
                        "float broken() { return missingValue; }\n"));
                FileDocumentManager.getInstance().saveAllDocuments();
            });
            assertTrue(files.size() > parallelism);

            for (String phase : List.of("cold", "warm", "open editors")) {
                queue.awaitIdle();
                if (phase.equals("open editors")) {
                    EdtTestUtil.runInEdtAndWait(() -> {
                        for (var file : files) {
                            fixture.configureFromExistingVirtualFile(file.getVirtualFile());
                        }
                    });
                }
                var indicator = new ProgressIndicatorBase();
                var stalled = new AtomicReference<String>();
                long start = System.nanoTime();
                try (var watchdog = Executors.newSingleThreadScheduledExecutor()) {
                    // Cancellation is a failure, not a successful analysis result. Capture before
                    // cancelling so the diagnostic distinguishes pool starvation from slow compilation.
                    var timeout = watchdog.schedule(() -> {
                        stalled.set("files=" + files.size() + ", progress=" + indicator.getText()
                                + ", externalQueue=" + queue.state()
                                + "\n" + threadSnapshot(parallelism));
                        indicator.cancel();
                    }, ANALYSIS_BUDGET_SECONDS, TimeUnit.SECONDS);
                    try {
                        var smells = new AtomicReference<List<CodeSmellInfo>>();
                        ProgressManager.getInstance().runProcess(() -> smells.set(
                                CodeSmellDetector.getInstance(fixture.getProject()).findCodeSmells(
                                        files.stream().map(PsiFile::getVirtualFile).toList())), indicator);
                        assertNull(stalled.get(), () -> phase + " exceeded the analysis budget:\n" + stalled.get());
                        assertTrue(smells.get().stream().anyMatch(s -> s.getDescription().contains("BadName")),
                                "Naming inspection must produce a real result");
                        assertTrue(smells.get().stream().anyMatch(s -> s.getDescription().contains("missingValue")),
                                "Compiler inspection must produce a real result");
                        assertFalse(smells.get().stream().anyMatch(s -> s.getDescription().contains("could not complete")),
                                () -> "Timeout/unavailable warnings are not successful analysis: " + smells.get());
                    } catch (ProcessCanceledException cancelled) {
                        fail(phase + " commit analysis did not complete within " + ANALYSIS_BUDGET_SECONDS
                                + " seconds:\n" + stalled.get(), cancelled);
                    } finally {
                        timeout.cancel(false);
                    }
                }
                System.out.println("SATURATED COMMIT ANALYSIS " + phase + " files=" + files.size()
                        + " parallelism=" + parallelism
                        + " elapsedMs=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            }
        } finally {
            try {
                NativeServerSupport.stop(fixture.getProject());
                queue.awaitIdle();
            } finally {
                queue.close();
                EdtTestUtil.runInEdtAndWait(fixture::tearDown);
            }
        }
    }

    private static String threadSnapshot(int parallelism) {
        Map<Thread, StackTraceElement[]> stacks = Thread.getAllStackTraces();
        long waitingPasses = stacks.values().stream().filter(stack -> contains(stack,
                "com.intellij.codeInsight.daemon.impl.ExternalToolPass", "getInfos")).count();
        StringBuilder result = new StringBuilder("commonPoolParallelism=").append(parallelism)
                .append(", externalToolPassWaiters=").append(waitingPasses)
                .append(", commonPool=").append(ForkJoinPool.commonPool()).append('\n');
        stacks.forEach((thread, stack) -> {
            if (contains(stack, "com.intellij.codeInsight.daemon.impl.ExternalToolPass", "getInfos")
                    || contains(stack, AnalysisDeadline.class.getName(), "await")
                    || java.util.Arrays.stream(stack).anyMatch(element ->
                            element.getClassName().equals("com.intellij.codeInsight.daemon.impl.ExternalToolPass"))
                    || thread.getName().startsWith("Slang batch")
                    || thread.getName().startsWith("ForkJoinPool.commonPool")
                    || thread.getName().startsWith("JobScheduler FJ pool")
                    || thread.getName().startsWith("LS-slanglsp")) {
                result.append('"').append(thread.getName()).append("\" ").append(thread.getState()).append('\n');
                for (var element : stack) {
                    result.append("    at ").append(element).append('\n');
                }
            }
        });
        return result.toString();
    }

    private static boolean contains(StackTraceElement[] stack, String type, String method) {
        for (var element : stack) {
            if (element.getClassName().equals(type) && element.getMethodName().equals(method)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The IDE enables pass-through specifically for unit tests. Restore the production
     * queue mode so commit workers wait exactly as they do in the running IDE.
     * Reflection is confined to tests because ExternalAnnotatorManager is package-private.
     */
    private static final class QueuedExternalInspections implements AutoCloseable {
        private final MergingUpdateQueue queue;
        private final boolean previous;
        private final boolean previouslySuspended;
        private final java.util.concurrent.ScheduledExecutorService drainer =
                Executors.newSingleThreadScheduledExecutor();

        private QueuedExternalInspections() throws ReflectiveOperationException {
            Class<?> type = Class.forName("com.intellij.codeInsight.daemon.impl.ExternalAnnotatorManager");
            Object manager = ApplicationManager.getApplication().getService(type);
            var field = type.getDeclaredField("myExternalActivitiesQueue");
            field.setAccessible(true);
            queue = (MergingUpdateQueue) field.get(manager);
            previous = queue.isPassThrough();
            previouslySuspended = queue.isSuspended();
            queue.setPassThrough(false);
            // The 2025.1 SingleAlarm cancels a running coroutine when another flush
            // is scheduled, dropping the rest of its drained update list. Drain the
            // SAME queue asynchronously, without that unrelated timer cancellation.
            // Workers still wait in the real ExternalToolPass.getInfos().
            queue.suspend();
            drainer.scheduleWithFixedDelay(queue::flush, 300, 50, TimeUnit.MILLISECONDS);
        }

        private void awaitIdle() throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!queue.isEmpty() || queue.isFlushing()) {
                if (System.nanoTime() >= deadline) {
                    throw new java.util.concurrent.TimeoutException("External inspection queue did not drain");
                }
                // The IDE inspection queue does not expose an idle future.
                //noinspection BusyWait
                Thread.sleep(10);
            }
        }

        private String state() {
            return "empty=" + queue.isEmpty() + ", flushing=" + queue.isFlushing()
                    + ", suspended=" + queue.isSuspended() + ", active=" + queue.isActive();
        }

        @Override
        public void close() {
            drainer.shutdownNow();
            try {
                if (!drainer.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("External inspection test drainer did not stop");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            queue.setPassThrough(previous);
            if (!previouslySuspended) {
                queue.resume();
            }
        }
    }
}

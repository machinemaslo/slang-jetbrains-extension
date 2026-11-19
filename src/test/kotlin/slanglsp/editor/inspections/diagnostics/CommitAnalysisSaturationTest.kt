package slanglsp.editor.inspections.diagnostics

import com.intellij.codeInsight.CodeSmellInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vcs.CodeSmellDetector
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.util.ui.update.MergingUpdateQueue
import java.nio.file.Path
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import slanglsp.FormattingFixtures
import slanglsp.TestDependencies
import slanglsp.analysis.AnalysisDeadline
import slanglsp.editor.NativeServerSupport
import slanglsp.editor.inspections.naming.InconsistentNamingInspection
import slanglsp.editor.inspections.unused.UnusedSymbolsInspection
import slanglsp.settings.project.ProjectSettings

/** Exercises the real queued external-tool pass, not its synchronous unit-test shortcut. */
internal class CommitAnalysisSaturationTest {
    @Test
    @Timeout(120)
    @Throws(Exception::class)
    fun queuedCommitChecksCompleteAboveCommonPoolParallelism() {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        val fixture =
            factory.createCodeInsightFixture(
                factory.createLightFixtureBuilder("CommitAnalysisSaturation").getFixture(), TempDirTestFixtureImpl())
        runInEdtAndWait { fixture.setUp() }
        val queue = QueuedExternalInspections()
        try {
            val parallelism = ForkJoinPool.getCommonPoolParallelism()
            val files = ArrayList<PsiFile>()
            runInEdtAndWait {
                ModuleRootModificationUtil.addContentRoot(fixture.module, fixture.tempDirPath)
                fixture.enableInspections(
                    BatchDiagnosticsInspection(), InconsistentNamingInspection(), UnusedSymbolsInspection())
                val state = ProjectSettings.getInstance(fixture.project).getState()
                state.explicitSlangdLocation = Path.of(TestDependencies.slangd()).parent.toString()
                state.useLocalVcpkgSlangd = false
                ProjectSettings.getInstance(fixture.project).loadState(state)
                NativeServerSupport.enable(fixture.project)
                // Keep the actual sources from the stalled commit, including their whitespace.
                for (i in 0..<parallelism + 8) {
                    val scenario = if (i % 2 == 0) "wrapping/before.slang" else "token-preservation/switch.slang"
                    files.add(fixture.addFileToProject("copy$i/$scenario", FormattingFixtures.text(scenario)))
                }
                files.add(
                    fixture.addFileToProject("naming.slang", "float BadName() { float unusedLocal; return 0; }\n"))
                files.add(fixture.addFileToProject("diagnostics.slang", "float broken() { return missingValue; }\n"))
                FileDocumentManager.getInstance().saveAllDocuments()
            }
            Assertions.assertTrue(files.size > parallelism)

            for (phase in mutableListOf<String?>("cold", "warm", "open editors")) {
                queue.awaitIdle()
                if (phase == "open editors") {
                    runInEdtAndWait {
                        for (file in files) {
                            fixture.configureFromExistingVirtualFile(file.virtualFile)
                        }
                    }
                }
                val indicator = ProgressIndicatorBase()
                val stalled = AtomicReference<String?>()
                val start = System.nanoTime()
                Executors.newSingleThreadScheduledExecutor().use { watchdog ->
                    // Cancellation is a failure, not a successful analysis result. Capture before
                    // cancelling so the diagnostic distinguishes pool starvation from slow compilation.
                    val timeout =
                        watchdog.schedule(
                            {
                                stalled.set(
                                    ("files=" +
                                        files.size +
                                        ", progress=" +
                                        indicator.text +
                                        ", externalQueue=" +
                                        queue.state() +
                                        "\n" +
                                        threadSnapshot(parallelism)))
                                indicator.cancel()
                            },
                            ANALYSIS_BUDGET_SECONDS.toLong(),
                            TimeUnit.SECONDS)
                    try {
                        val smells = AtomicReference<MutableList<CodeSmellInfo?>?>()
                        ProgressManager.getInstance()
                            .runProcess(
                                {
                                    smells.set(
                                        CodeSmellDetector.getInstance(fixture.project)
                                            .findCodeSmells(
                                                files
                                                    .stream()
                                                    .map<VirtualFile?> { obj: PsiFile? -> obj!!.virtualFile }
                                                    .toList()))
                                },
                                indicator)
                        Assertions.assertNull(stalled.get()) {
                            phase + " exceeded the analysis budget:\n" + stalled.get()
                        }
                        Assertions.assertTrue(
                            smells.get()!!.stream().anyMatch { s: CodeSmellInfo? ->
                                s!!.description.contains("BadName")
                            },
                            "Naming inspection must produce a real result")
                        Assertions.assertTrue(
                            smells.get()!!.stream().anyMatch { s: CodeSmellInfo? ->
                                s!!.description.contains("missingValue")
                            },
                            "Compiler inspection must produce a real result")
                        Assertions.assertFalse(
                            smells.get()!!.stream().anyMatch { s: CodeSmellInfo? ->
                                s!!.description.contains("could not complete")
                            }) {
                                "Timeout/unavailable warnings are not successful analysis: " + smells.get()
                            }
                    } catch (cancelled: ProcessCanceledException) {
                        Assertions.fail<Any?>(
                            (phase +
                                " commit analysis did not complete within " +
                                ANALYSIS_BUDGET_SECONDS +
                                " seconds:\n" +
                                stalled.get()),
                            cancelled)
                    } finally {
                        timeout.cancel(false)
                    }
                }
                println(
                    ("SATURATED COMMIT ANALYSIS " +
                        phase +
                        " files=" +
                        files.size +
                        " parallelism=" +
                        parallelism +
                        " elapsedMs=" +
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)))
            }
        } finally {
            try {
                NativeServerSupport.stop(fixture.project)
                queue.awaitIdle()
            } finally {
                queue.close()
                runInEdtAndWait { fixture.tearDown() }
            }
        }
    }

    /**
     * The IDE enables pass-through specifically for unit tests. Restore the production queue mode so commit workers
     * wait exactly as they do in the running IDE. Reflection is confined to tests because ExternalAnnotatorManager is
     * package-private.
     */
    private class QueuedExternalInspections : AutoCloseable {
        private val queue: MergingUpdateQueue
        private val previous: Boolean
        private val previouslySuspended: Boolean
        private val drainer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

        init {
            val type = Class.forName("com.intellij.codeInsight.daemon.impl.ExternalAnnotatorManager")
            val manager: Any? = ApplicationManager.getApplication().getService(type)
            val field = type.getDeclaredField("myExternalActivitiesQueue")
            field.setAccessible(true)
            queue = field.get(manager) as MergingUpdateQueue
            previous = queue.isPassThrough
            previouslySuspended = queue.isSuspended
            queue.isPassThrough = false
            // The 2025.1 SingleAlarm cancels a running coroutine when another flush
            // is scheduled, dropping the rest of its drained update list. Drain the
            // SAME queue asynchronously, without that unrelated timer cancellation.
            // Workers still wait in the real ExternalToolPass.getInfos().
            queue.suspend()
            drainer.scheduleWithFixedDelay({ queue.flush() }, 300, 50, TimeUnit.MILLISECONDS)
        }

        @Throws(Exception::class)
        fun awaitIdle() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!queue.isEmpty || queue.isFlushing) {
                if (System.nanoTime() >= deadline) {
                    throw TimeoutException("External inspection queue did not drain")
                }
                // The IDE inspection queue does not expose an idle future.
                Thread.sleep(10)
            }
        }

        fun state(): String {
            return ("empty=" +
                queue.isEmpty +
                ", flushing=" +
                queue.isFlushing +
                ", suspended=" +
                queue.isSuspended +
                ", active=" +
                queue.isActive)
        }

        override fun close() {
            drainer.shutdownNow()
            try {
                if (!drainer.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw AssertionError("External inspection test drainer did not stop")
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw AssertionError(interrupted)
            }
            queue.isPassThrough = previous
            if (!previouslySuspended) {
                queue.resume()
            }
        }
    }

    companion object {
        private const val ANALYSIS_BUDGET_SECONDS = 8

        private fun threadSnapshot(parallelism: Int): String {
            val stacks = Thread.getAllStackTraces()
            val waitingPasses =
                stacks.values
                    .stream()
                    .filter { stack: Array<StackTraceElement>? ->
                        contains(stack!!, "com.intellij.codeInsight.daemon.impl.ExternalToolPass", "getInfos")
                    }
                    .count()
            val result =
                StringBuilder("commonPoolParallelism=")
                    .append(parallelism)
                    .append(", externalToolPassWaiters=")
                    .append(waitingPasses)
                    .append(", commonPool=")
                    .append(ForkJoinPool.commonPool())
                    .append('\n')
            stacks.forEach { (thread: Thread?, stack: Array<StackTraceElement>?) ->
                if (contains(stack!!, "com.intellij.codeInsight.daemon.impl.ExternalToolPass", "getInfos") ||
                    contains(stack, AnalysisDeadline::class.java.getName(), "await") ||
                    Arrays.stream<StackTraceElement?>(stack).anyMatch { element: StackTraceElement? ->
                        element!!.className == "com.intellij.codeInsight.daemon.impl.ExternalToolPass"
                    } ||
                    thread!!.name.startsWith("Slang batch") ||
                    thread.name.startsWith("ForkJoinPool.commonPool") ||
                    thread.name.startsWith("JobScheduler FJ pool") ||
                    thread.name.startsWith("LS-slanglsp")) {
                    result.append('"').append(thread!!.name).append("\" ").append(thread.state).append('\n')
                    for (element in stack) {
                        result.append("    at ").append(element).append('\n')
                    }
                }
            }
            return result.toString()
        }

        private fun contains(stack: Array<StackTraceElement>, type: String, method: String): Boolean {
            for (element in stack) {
                if (element.className == type && element.methodName == method) {
                    return true
                }
            }
            return false
        }
    }
}

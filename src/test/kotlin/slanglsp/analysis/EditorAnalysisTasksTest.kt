package slanglsp.analysis

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.runInEdtAndWait
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class EditorAnalysisTasksTest {
    @Test
    fun cancellationInterruptsAnalysisAndSkipsTheEdtCallback() {
        val fixture =
            IdeaTestFixtureFactory.getFixtureFactory().createLightFixtureBuilder("EditorAnalysisTasks").fixture
        runInEdtAndWait { fixture.setUp() }

        try {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val applied = AtomicBoolean(false)
            val reported = AtomicBoolean(false)
            val job =
                fixture.project.service<EditorAnalysisTasks>().launch(
                    "Test editor analysis",
                    onFailure = { reported.set(true) },
                    compute = {
                        started.countDown()
                        release.await()
                    }) {
                        applied.set(true)
                    }
            try {
                job.invokeOnCompletion { finished.countDown() }
                assertTrue(started.await(10, SECONDS), "Analysis did not start")
                job.cancel()
                assertTrue(finished.await(10, SECONDS), "Cancellation did not interrupt the waiting analysis")
                assertTrue(job.isCancelled)
                assertFalse(applied.get(), "Canceled analysis must not apply an edit")
                assertFalse(reported.get(), "Cancellation is not an analysis failure")
            } finally {
                job.cancel()
                release.countDown()
            }
        } finally {
            runInEdtAndWait { fixture.tearDown() }
        }
    }

    @Test
    fun analysisFailureIsReportedOnTheEdtWithoutApplyingAnEdit() {
        val fixture =
            IdeaTestFixtureFactory.getFixtureFactory().createLightFixtureBuilder("EditorAnalysisFailure").fixture
        runInEdtAndWait { fixture.setUp() }

        try {
            val finished = CountDownLatch(1)
            val report = AtomicReference<Pair<String?, Boolean>>()
            val applied = AtomicBoolean(false)
            val job =
                fixture.project.service<EditorAnalysisTasks>().launch<Unit>(
                    "Test editor analysis",
                    onFailure = { failure ->
                        report.set(failure.message to ApplicationManager.getApplication().isDispatchThread)
                    },
                    compute = { error("Verification failed") }) {
                        applied.set(true)
                    }
            try {
                job.invokeOnCompletion { finished.countDown() }
                assertTrue(finished.await(10, SECONDS))
                assertEquals("Verification failed" to true, report.get())
                assertFalse(applied.get())
            } finally {
                job.cancel()
            }
        } finally {
            runInEdtAndWait { fixture.tearDown() }
        }
    }
}

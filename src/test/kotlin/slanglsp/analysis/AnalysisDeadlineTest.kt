package slanglsp.analysis

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.runInEdtAndWait
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeoutException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import slanglsp.analysis.AnalysisDeadline.Companion.open

internal class AnalysisDeadlineTest {
    private var fixture: IdeaProjectTestFixture? = null

    @BeforeEach
    @Throws(Exception::class)
    fun setUp() {
        fixture = IdeaTestFixtureFactory.getFixtureFactory().createLightFixtureBuilder("AnalysisDeadline").getFixture()
        runInEdtAndWait { fixture!!.setUp() }
    }

    @AfterEach
    @Throws(Exception::class)
    fun tearDown() {
        runInEdtAndWait { fixture!!.tearDown() }
    }

    @Test
    fun nestedScopeCannotExtendBudgetAndClosingRestoresOuterScope() {
        open(Duration.ofSeconds(5)).use {
            open(Duration.ZERO).use {
                open(Duration.ofSeconds(10)).use {
                    val failure =
                        Assertions.assertThrows(CompletionException::class.java) {
                            AnalysisDeadline.await(CompletableFuture<String?>())
                        }
                    Assertions.assertInstanceOf(TimeoutException::class.java, failure.cause)
                }
            }
            Assertions.assertEquals("ready", AnalysisDeadline.await(CompletableFuture.completedFuture("ready")))
        }
    }

    @Test
    fun cancellationStopsWaitingWithoutCancellingSharedRequest() {
        val shared = CompletableFuture<String?>()
        val indicator = EmptyProgressIndicator()
        Assertions.assertThrows(ProcessCanceledException::class.java) {
            ProgressManager.getInstance()
                .runProcess(
                    {
                        indicator.cancel()
                        AnalysisDeadline.await(shared)
                    },
                    indicator)
        }
        Assertions.assertFalse(shared.isDone)
    }

    @Test
    fun timeoutDoesNotPoisonSharedRequest() {
        val shared = CompletableFuture<String?>()
        open(Duration.ofMillis(30)).use {
            val failure =
                Assertions.assertThrows(CompletionException::class.java) {
                    AnalysisDeadline.await(shared)
                }
            Assertions.assertInstanceOf(TimeoutException::class.java, failure.cause)
        }
        Assertions.assertFalse(shared.isDone)
        shared.complete("later")
        Assertions.assertEquals("later", AnalysisDeadline.await(shared))
    }
}

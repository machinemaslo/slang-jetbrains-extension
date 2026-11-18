package slanglsp.editor;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("try") // These tests observe effects of entering and closing nested scopes.
class AnalysisDeadlineTest {
    private com.intellij.testFramework.fixtures.IdeaProjectTestFixture fixture;

    @org.junit.jupiter.api.BeforeEach
    void setUp() throws Exception {
        fixture = com.intellij.testFramework.fixtures.IdeaTestFixtureFactory.getFixtureFactory()
                .createLightFixtureBuilder("AnalysisDeadline").getFixture();
        com.intellij.testFramework.EdtTestUtil.runInEdtAndWait(fixture::setUp);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() throws Exception {
        com.intellij.testFramework.EdtTestUtil.runInEdtAndWait(fixture::tearDown);
    }

    @Test
    void nestedScopeCannotExtendBudgetAndClosingRestoresOuterScope() {
        try (@SuppressWarnings("unused") var outer = AnalysisDeadline.open(Duration.ofSeconds(5))) {
            try (@SuppressWarnings("unused") var expired = AnalysisDeadline.open(Duration.ZERO);
                 @SuppressWarnings("unused") var nested = AnalysisDeadline.open(Duration.ofSeconds(10))) {
                var failure = assertThrows(CompletionException.class,
                        () -> AnalysisDeadline.await(new CompletableFuture<>()));
                assertInstanceOf(TimeoutException.class, failure.getCause());
            }
            assertEquals("ready", AnalysisDeadline.await(CompletableFuture.completedFuture("ready")));
        }
    }

    @Test
    void cancellationStopsWaitingWithoutCancellingSharedRequest() {
        var shared = new CompletableFuture<String>();
        var indicator = new com.intellij.openapi.progress.EmptyProgressIndicator();
        assertThrows(com.intellij.openapi.progress.ProcessCanceledException.class,
                () -> com.intellij.openapi.progress.ProgressManager.getInstance().runProcess(
                        () -> {
                            indicator.cancel();
                            return AnalysisDeadline.await(shared);
                        }, indicator));
        assertFalse(shared.isDone());
    }

    @Test
    void timeoutDoesNotPoisonSharedRequest() {
        var shared = new CompletableFuture<String>();
        try (@SuppressWarnings("unused") var scope = AnalysisDeadline.open(Duration.ofMillis(30))) {
            var failure = assertThrows(CompletionException.class, () -> AnalysisDeadline.await(shared));
            assertInstanceOf(TimeoutException.class, failure.getCause());
        }
        assertFalse(shared.isDone());
        shared.complete("later");
        assertEquals("later", AnalysisDeadline.await(shared));
    }
}

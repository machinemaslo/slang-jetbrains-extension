package slanglsp.editor;

import com.intellij.openapi.progress.util.ProgressIndicatorUtils;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.time.Duration;

/** One shared budget for an annotator and all of its nested reference/definition requests. */
final class AnalysisDeadline implements AutoCloseable {
    private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();
    private final Long previous = DEADLINE.get();

    private AnalysisDeadline(Duration budget) {
        long requested = System.nanoTime() + budget.toNanos();
        DEADLINE.set(previous == null ? requested : Math.min(previous, requested));
    }

    static AnalysisDeadline open() {
        return open(Duration.ofSeconds(10));
    }

    static AnalysisDeadline open(Duration budget) {
        return new AnalysisDeadline(budget);
    }

    @SuppressWarnings("try") // Closing the scope restores the previous deadline.
    static <T> T await(CompletableFuture<T> future) {
        Long deadline = DEADLINE.get();
        if (deadline == null) {
            try (@SuppressWarnings("unused") var scope = open()) {
                return await(future);
            }
        }
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new CompletionException(new TimeoutException("Slang analysis timed out"));
        }
        // A private dependent future avoids poisoning LSP4IJ's shared request cache.
        var bounded = future.copy().orTimeout(remaining, TimeUnit.NANOSECONDS);
        try {
            return ProgressIndicatorUtils.awaitWithCheckCanceled(bounded);
        } catch (com.intellij.openapi.progress.ProcessCanceledException cancelled) {
            throw cancelled;
        } catch (RuntimeException failure) {
            Throwable cause = failure;
            while (cause.getCause() != null && cause != cause.getCause()) {
                cause = cause.getCause();
            }
            if (cause instanceof TimeoutException) {
                throw new CompletionException(cause);
            }
            throw failure;
        }
    }

    @Override
    public void close() {
        if (previous == null) {
            DEADLINE.remove();
        } else {
            DEADLINE.set(previous);
        }
    }
}

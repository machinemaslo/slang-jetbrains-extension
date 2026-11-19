package slanglsp.analysis

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.min

/** One shared budget for an annotator and all of its nested reference/definition requests. */
internal class AnalysisDeadline private constructor(budget: Duration) : AutoCloseable {
    private val previous: Long? = DEADLINE.get()

    init {
        val requested = System.nanoTime() + budget.toNanos()
        DEADLINE.set(if (previous == null) requested else min(previous, requested))
    }

    override fun close() {
        if (previous == null) {
            DEADLINE.remove()
        } else {
            DEADLINE.set(previous)
        }
    }

    companion object {
        private val DEADLINE = ThreadLocal<Long?>()

        @JvmStatic
        @JvmOverloads
        fun open(budget: Duration = Duration.ofSeconds(10)): AnalysisDeadline {
            return AnalysisDeadline(budget)
        }

        fun <T> await(future: CompletableFuture<T?>?): T? {
            val deadline: Long =
                DEADLINE.get()
                    ?: open().use {
                        return await<T?>(future)
                    }
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) {
                throw CompletionException(TimeoutException("Slang analysis timed out"))
            }
            // A private dependent future avoids poisoning LSP4IJ's shared request cache.
            val bounded = future!!.copy().orTimeout(remaining, TimeUnit.NANOSECONDS)
            try {
                return ProgressIndicatorUtils.awaitWithCheckCanceled(bounded)
            } catch (cancelled: ProcessCanceledException) {
                throw cancelled
            } catch (failure: RuntimeException) {
                var cause: Throwable = failure
                while (cause.cause != null && cause !== cause.cause) {
                    cause = cause.cause!!
                }
                if (cause is TimeoutException) {
                    throw CompletionException(cause)
                }
                throw failure
            }
        }
    }
}

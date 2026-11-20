package slanglsp.analysis

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.toKotlinDuration

/** One shared budget for an annotator and all of its nested reference/definition requests. */
internal class AnalysisDeadline private constructor(budget: Duration) : AutoCloseable {
    private val previous = DEADLINE.get()

    init {
        val requested = TimeSource.Monotonic.markNow() + budget
        DEADLINE.set(previous?.let { minOf(it, requested) } ?: requested)
    }

    override fun close() {
        if (previous == null) {
            DEADLINE.remove()
        } else {
            DEADLINE.set(previous)
        }
    }

    companion object {
        private val DEADLINE = ThreadLocal<ComparableTimeMark?>()

        @JvmStatic
        fun open(): AnalysisDeadline {
            return open(10.seconds)
        }

        @JvmStatic
        fun open(budget: Duration): AnalysisDeadline {
            return AnalysisDeadline(budget)
        }

        @JvmStatic
        fun open(budget: java.time.Duration): AnalysisDeadline {
            return open(budget.toKotlinDuration())
        }

        fun <T> await(future: CompletableFuture<T?>?): T? {
            val deadline =
                DEADLINE.get()
                    ?: open().use {
                        return await(future)
                    }
            val remaining = -deadline.elapsedNow()
            if (remaining <= Duration.ZERO) {
                throw CompletionException(TimeoutException("Slang analysis timed out"))
            }

            // A private dependent future avoids cancelling another consumer's shared request.
            val bounded = future!!.copy().orTimeout(remaining.inWholeNanoseconds, TimeUnit.NANOSECONDS)
            try {
                return ProgressIndicatorUtils.awaitWithCheckCanceled(bounded)
            } catch (cancelled: ProcessCanceledException) {
                throw cancelled
            } catch (failure: RuntimeException) {
                var cause: Throwable = failure
                while (true) {
                    val next = cause.cause ?: break
                    if (next === cause) {
                        break
                    }
                    cause = next
                }

                if (cause is TimeoutException) {
                    throw CompletionException(cause)
                }
                throw failure
            }
        }
    }
}

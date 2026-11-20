package slanglsp.analysis

import com.intellij.openapi.diagnostic.Logger
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeSource

/** Enable #slanglsp.editor.AnalysisTiming in the IDE's Debug Log Settings. */
@OptIn(ExperimentalAtomicApi::class)
internal class AnalysisTiming private constructor(
    private val operation: String,
    file: String,
) : AutoCloseable {
    private val id = NEXT_ID.fetchAndAdd(1)
    private val started = TimeSource.Monotonic.markNow()
    private var references = 0
    private var referenceTime = Duration.ZERO

    init {
        LOG.debug("Slang analysis #$id started: $operation file=$file thread=${Thread.currentThread().name}")
    }

    fun referenceCompleted(elapsedNanos: Long) {
        references++
        referenceTime += elapsedNanos.nanoseconds
    }

    override fun close() {
        val elapsedMs = started.elapsedNow().inWholeMilliseconds
        val referenceStats =
            if (references == 0) {
                ""
            } else {
                " referenceChecks=$references referenceMs=${referenceTime.inWholeMilliseconds}"
            }

        LOG.debug("Slang analysis #$id finished: $operation elapsedMs=$elapsedMs$referenceStats")
    }

    companion object {
        // Keep the category stable for existing Debug Log Settings.
        private val LOG = Logger.getInstance("#slanglsp.editor.AnalysisTiming")
        private val NEXT_ID = AtomicInt(1)

        fun start(operation: String, file: String): AnalysisTiming? {
            return if (LOG.isDebugEnabled) AnalysisTiming(operation, file) else null
        }
    }
}

package slanglsp.analysis

import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.atomic.AtomicInteger

/** Enable #slanglsp.editor.AnalysisTiming in the IDE's Debug Log Settings. */
internal class AnalysisTiming private constructor(private val operation: String, file: String) : AutoCloseable {
    private val id: Int = NEXT_ID.incrementAndGet()
    private val started = System.nanoTime()
    private var references = 0
    private var referenceNanos: Long = 0

    init {
        LOG.debug(
            ("Slang analysis #" +
                id +
                " started: " +
                operation +
                " file=" +
                file +
                " thread=" +
                Thread.currentThread().name))
    }

    fun referenceCompleted(elapsedNanos: Long) {
        references++
        referenceNanos += elapsedNanos
    }

    override fun close() {
        LOG.debug(
            ("Slang analysis #" +
                id +
                " finished: " +
                operation +
                " elapsedMs=" +
                (System.nanoTime() - started) / 1000000 +
                (if (references == 0) ""
                else " referenceChecks=" + references + " referenceMs=" + referenceNanos / 1000000)))
    }

    companion object {
        // Keep the category stable for existing Debug Log Settings.
        private val LOG = Logger.getInstance("#slanglsp.editor.AnalysisTiming")
        private val NEXT_ID = AtomicInteger()

        fun start(operation: String, file: String): AnalysisTiming? {
            return if (LOG.isDebugEnabled) AnalysisTiming(operation, file) else null
        }
    }
}

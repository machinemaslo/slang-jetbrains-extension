package slanglsp.analysis

import com.intellij.openapi.application.runReadActionBlocking

/** Short blocking PSI reads for callers that need a synchronous snapshot. */
internal object ReadActions {
    fun <T> compute(action: () -> T): T {
        return runReadActionBlocking(action)
    }

    fun run(action: () -> Unit) {
        runReadActionBlocking(action)
    }
}

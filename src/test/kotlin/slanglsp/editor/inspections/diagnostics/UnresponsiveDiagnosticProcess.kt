package slanglsp.editor.inspections.diagnostics

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch

/** Separate JVM used to exercise process cancellation/timeout on Windows as well as Unix. */
object UnresponsiveDiagnosticProcess {
    @Throws(Exception::class)
    @JvmStatic
    fun main(arguments: Array<String>) {
        Files.writeString(Path.of(arguments[0]), ProcessHandle.current().pid().toString())
        CountDownLatch(1).await()
    }
}

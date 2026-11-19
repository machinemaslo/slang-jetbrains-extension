package slanglsp.server.client

import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.Any
import kotlin.String
import kotlin.Throws
import kotlin.concurrent.Volatile

/** Slangd has server-wide formatting options, including during startup configuration requests. */
@Service(Service.Level.PROJECT)
class FormattingSettings {
    private val lock = ReentrantLock()

    @Volatile private var active: Map<String?, Any?> = emptyMap()

    @Throws(InterruptedException::class, TimeoutException::class)
    fun open(settings: Map<String?, Any?>, cancelled: () -> Boolean): AutoCloseable {
        val options = settings.filterKeys { it?.startsWith("slang.format.") == true }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!lock.tryLock(50, TimeUnit.MILLISECONDS)) {
            ProgressManager.checkCanceled()
            if (cancelled()) {
                throw ProcessCanceledException()
            }
            if (System.nanoTime() >= deadline) {
                throw TimeoutException("Waiting for Slang formatting settings timed out")
            }
        }
        val previous = active
        active = options
        return AutoCloseable {
            active = previous
            lock.unlock()
        }
    }

    fun apply(settings: MutableMap<String?, Any?>) {
        settings.putAll(active)
    }
}

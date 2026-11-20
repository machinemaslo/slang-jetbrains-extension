package slanglsp.discovery

import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Cache both hits and misses. Expiry is checked only on server start, never by a timer/build hook. */
internal class DiscoveryCache(
    private val discover: () -> String?,
    private val lifetime: Duration,
    private val clock: TimeSource = TimeSource.Monotonic,
) {
    private data class Entry(val executable: String?, val expiresAt: TimeMark)

    private var entry: Entry? = null

    @Synchronized
    fun get(): String? {
        val cached = entry

        if (cached != null && cached.expiresAt.hasNotPassedNow()) {
            return cached.executable
        }

        val expiresAt = clock.markNow() + lifetime
        val executable = discover()
        entry = Entry(executable, expiresAt)

        return executable
    }

    @Synchronized
    fun invalidate() {
        entry = null
    }
}

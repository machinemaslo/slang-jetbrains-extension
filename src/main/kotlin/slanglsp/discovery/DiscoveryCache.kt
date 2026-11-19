package slanglsp.discovery

/** Cache both hits and misses. Expiry is checked only on server start, never by a timer/build hook. */
internal class DiscoveryCache(
    private val discover: () -> String?,
    private val clock: () -> Long,
    private val lifetime: Long
) {
    private data class Entry(val executable: String?, val checkedAt: Long)

    private var entry: Entry? = null

    @Synchronized
    fun get(): String? {
        val now = clock()
        val cached = entry
        if (cached != null && now - cached.checkedAt < lifetime) {
            return cached.executable
        }

        val executable = discover()
        entry = Entry(executable, now)
        return executable
    }

    @Synchronized
    fun invalidate() {
        entry = null
    }
}

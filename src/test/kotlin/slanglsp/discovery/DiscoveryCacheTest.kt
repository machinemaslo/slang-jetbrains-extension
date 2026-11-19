package slanglsp.discovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DiscoveryCacheTest {
    @Test
    fun cachesMissesAndHitsUntilExpiryOrInvalidation() {
        var now = 0L
        var executable: String? = null
        var lookups = 0
        val cache =
            DiscoveryCache(
                discover = {
                    lookups++
                    executable
                },
                clock = { now },
                lifetime = 10L)

        assertNull(cache.get())
        executable = "first"
        now = 9
        assertNull(cache.get())
        assertEquals(1, lookups)

        now = 10
        assertEquals("first", cache.get())
        executable = "second"
        assertEquals("first", cache.get())
        assertEquals(2, lookups)

        cache.invalidate()
        assertEquals("second", cache.get())
        assertEquals(3, lookups)
    }
}

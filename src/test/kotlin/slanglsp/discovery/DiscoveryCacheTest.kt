package slanglsp.discovery

import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TestTimeSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DiscoveryCacheTest {
    @Test
    fun cachesMissesAndHitsUntilExpiryOrInvalidation() {
        val clock = TestTimeSource()
        var executable: String? = null
        var lookups = 0

        val cache = DiscoveryCache(
            discover = {
                lookups++
                executable
            },
            lifetime = 10.nanoseconds,
            clock = clock,
        )

        assertNull(cache.get())
        executable = "first"
        clock += 9.nanoseconds
        assertNull(cache.get())
        assertEquals(1, lookups)

        clock += 1.nanoseconds
        assertEquals("first", cache.get())
        executable = "second"
        assertEquals("first", cache.get())
        assertEquals(2, lookups)

        cache.invalidate()
        assertEquals("second", cache.get())
        assertEquals(3, lookups)
    }
}

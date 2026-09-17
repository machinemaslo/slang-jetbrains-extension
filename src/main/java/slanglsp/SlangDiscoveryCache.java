package slanglsp;

import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Cache both hits and misses. Expiry is checked only on server start, never by a timer/build hook. */
final class SlangDiscoveryCache {
    private final Supplier<Optional<String>> discover;
    private final LongSupplier clock;
    private final long lifetime;
    private Optional<String> value;
    private long checkedAt;

    SlangDiscoveryCache(Supplier<Optional<String>> discover, LongSupplier clock, long lifetime) {
        this.discover = discover;
        this.clock = clock;
        this.lifetime = lifetime;
    }

    synchronized Optional<String> get() {
        long now = clock.getAsLong();
        if (value == null || now - checkedAt >= lifetime) {
            value = discover.get();
            checkedAt = now;
        }
        return value;
    }

    synchronized void invalidate() {
        value = null;
    }
}

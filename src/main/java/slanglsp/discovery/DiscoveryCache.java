package slanglsp.discovery;

import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Cache both hits and misses. Expiry is checked only on server start, never by a timer/build hook. */
final class DiscoveryCache {
    private final Supplier<Optional<String>> discover;
    private final LongSupplier clock;
    private final long lifetime;
    private String value;
    private boolean initialized;
    private long checkedAt;

    DiscoveryCache(Supplier<Optional<String>> discover, LongSupplier clock, long lifetime) {
        this.discover = discover;
        this.clock = clock;
        this.lifetime = lifetime;
    }

    synchronized Optional<String> get() {
        long now = clock.getAsLong();
        if (!initialized || now - checkedAt >= lifetime) {
            value = discover.get().orElse(null);
            initialized = true;
            checkedAt = now;
        }
        return Optional.ofNullable(value);
    }

    synchronized void invalidate() {
        initialized = false;
        value = null;
    }
}

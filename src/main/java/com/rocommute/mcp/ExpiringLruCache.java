package com.rocommute.mcp;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A small thread-safe in-memory cache: entries expire after a fixed time-to-live, and once full
 * the least-recently-used entry is evicted. Hand-rolled on {@link LinkedHashMap} (which has LRU
 * ordering built in) rather than pulling in a caching library, since this server needs exactly one
 * tiny cache and every added dependency is another thing to keep patched.
 *
 * <p>Takes a {@link Clock} rather than reading the wall clock directly so expiry is testable
 * without sleeping.
 */
final class ExpiringLruCache<V> {

    private record CachedValue<V>(V value, Instant expiresAt) {}

    private final Clock clock;
    private final Duration timeToLive;
    private final Map<String, CachedValue<V>> entries;

    ExpiringLruCache(Clock clock, Duration timeToLive, int maxEntries) {
        this.clock = clock;
        this.timeToLive = timeToLive;
        // accessOrder=true makes iteration order least-recently-used first, which is what
        // removeEldestEntry evicts.
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedValue<V>> eldest) {
                return size() > maxEntries;
            }
        };
    }

    /** The cached value for {@code key}, or empty if absent or expired (an expired entry is dropped). */
    synchronized Optional<V> get(String key) {
        var entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (!clock.instant().isBefore(entry.expiresAt())) {
            entries.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    synchronized void put(String key, V value) {
        entries.put(key, new CachedValue<>(value, clock.instant().plus(timeToLive)));
    }
}

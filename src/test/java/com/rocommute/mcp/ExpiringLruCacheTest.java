package com.rocommute.mcp;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ExpiringLruCacheTest {

    private static final Duration TTL = Duration.ofMinutes(10);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

    @Test
    void missingKey_isEmpty() {
        var cache = new ExpiringLruCache<String>(clock, TTL, 5);

        assertThat(cache.get("absent")).isEmpty();
    }

    @Test
    void storedValue_isReturned() {
        var cache = new ExpiringLruCache<String>(clock, TTL, 5);

        cache.put("key", "value");

        assertThat(cache.get("key")).contains("value");
    }

    @Test
    void putAgain_replacesValueAndRestartsItsLifetime() {
        var cache = new ExpiringLruCache<String>(clock, TTL, 5);
        cache.put("key", "old");
        clock.advance(TTL.minusSeconds(1));

        cache.put("key", "new");
        clock.advance(Duration.ofSeconds(30));

        assertThat(cache.get("key")).contains("new");
    }

    @Test
    void entry_isValidUntilExactlyItsTimeToLive_thenExpires() {
        var cache = new ExpiringLruCache<String>(clock, TTL, 5);
        cache.put("key", "value");

        clock.advance(TTL.minusNanos(1));
        assertThat(cache.get("key")).contains("value");

        clock.advance(Duration.ofNanos(1));
        assertThat(cache.get("key")).isEmpty();
    }

    @Test
    void full_evictsLeastRecentlyUsed_andReadingRefreshesRecency() {
        var cache = new ExpiringLruCache<String>(clock, TTL, 2);
        cache.put("a", "1");
        cache.put("b", "2");
        cache.get("a"); // "b" is now the least recently used

        cache.put("c", "3");

        assertThat(cache.get("b")).isEmpty();
        assertThat(cache.get("a")).contains("1");
        assertThat(cache.get("c")).contains("3");
    }
}

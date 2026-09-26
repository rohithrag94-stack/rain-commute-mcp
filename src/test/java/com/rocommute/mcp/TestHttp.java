package com.rocommute.mcp;

import java.time.Duration;

/** Shared test wiring for the HTTP layer, so every test fails fast instead of waiting on real backoff delays. */
final class TestHttp {

    private TestHttp() {}

    /** A fetcher with a short timeout and 1 ms backoff, retrying {@code maxRetries} times on transient failures. */
    static ResilientJsonFetcher fetcher(int maxRetries) {
        return new ResilientJsonFetcher(properties(maxRetries));
    }

    static RainCommuteProperties properties(int maxRetries) {
        var properties = new RainCommuteProperties();
        properties.setHttpTimeout(Duration.ofSeconds(2));
        properties.setHttpMaxRetries(maxRetries);
        properties.setHttpRetryBackoff(Duration.ofMillis(1));
        return properties;
    }
}

package com.rocommute.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * User-configurable settings, bound from the {@code rain-commute.*} properties -- typically the
 * external {@code ~/.rain-commute-mcp/config.properties} file layered on top of the bundled
 * defaults (see {@code spring.config.import} in {@code application.properties}).
 */
@Component
@ConfigurationProperties(prefix = "rain-commute")
public class RainCommuteProperties {

    /** Location shortcuts: {@code rain-commute.locations.home=Bengaluru} lets "home" stand for a real place name. */
    private Map<String, String> locations = Map.of();

    /** Commute duration used when a request doesn't specify one. */
    private int defaultCommuteMinutes = 30;

    /** How long a single request to Open-Meteo may take before that attempt is abandoned. */
    private Duration httpTimeout = Duration.ofSeconds(5);

    /** How many times a transient failure (timeout, dropped connection, 5xx, 429) is retried before giving up. */
    private int httpMaxRetries = 2;

    /** Delay before the first retry; doubles on each further retry. */
    private Duration httpRetryBackoff = Duration.ofMillis(300);

    public Map<String, String> getLocations() {
        return locations;
    }

    public void setLocations(Map<String, String> locations) {
        this.locations = locations;
    }

    public int getDefaultCommuteMinutes() {
        return defaultCommuteMinutes;
    }

    public void setDefaultCommuteMinutes(int defaultCommuteMinutes) {
        this.defaultCommuteMinutes = defaultCommuteMinutes;
    }

    public Duration getHttpTimeout() {
        return httpTimeout;
    }

    public void setHttpTimeout(Duration httpTimeout) {
        this.httpTimeout = httpTimeout;
    }

    public int getHttpMaxRetries() {
        return httpMaxRetries;
    }

    public void setHttpMaxRetries(int httpMaxRetries) {
        this.httpMaxRetries = httpMaxRetries;
    }

    public Duration getHttpRetryBackoff() {
        return httpRetryBackoff;
    }

    public void setHttpRetryBackoff(Duration httpRetryBackoff) {
        this.httpRetryBackoff = httpRetryBackoff;
    }
}

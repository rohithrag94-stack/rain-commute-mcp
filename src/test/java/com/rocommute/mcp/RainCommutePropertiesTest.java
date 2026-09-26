package com.rocommute.mcp;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * These defaults are also incidentally exercised via {@link CommuteWeatherServiceTest}, but get
 * their own direct test here (matching {@link WeatherClientConfigTest}'s convention of testing
 * each collaborator directly) so a regression in the defaults themselves is easy to find.
 */
class RainCommutePropertiesTest {

    @Test
    void freshInstance_hasNoLocationsAndThirtyMinuteDefault() {
        var properties = new RainCommuteProperties();

        assertThat(properties.getLocations()).isEmpty();
        assertThat(properties.getDefaultCommuteMinutes()).isEqualTo(30);
    }

    @Test
    void setLocations_isReflectedByGetter() {
        var properties = new RainCommuteProperties();

        properties.setLocations(Map.of("home", "Bengaluru", "work", "Electronic City, Bengaluru"));

        assertThat(properties.getLocations())
                .containsEntry("home", "Bengaluru")
                .containsEntry("work", "Electronic City, Bengaluru");
    }

    @Test
    void setDefaultCommuteMinutes_isReflectedByGetter() {
        var properties = new RainCommuteProperties();

        properties.setDefaultCommuteMinutes(45);

        assertThat(properties.getDefaultCommuteMinutes()).isEqualTo(45);
    }

    @Test
    void freshInstance_hasConservativeHttpResilienceDefaults() {
        var properties = new RainCommuteProperties();

        assertThat(properties.getHttpTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.getHttpMaxRetries()).isEqualTo(2);
        assertThat(properties.getHttpRetryBackoff()).isEqualTo(Duration.ofMillis(300));
    }

    @Test
    void httpSettersAreReflectedByGetters() {
        var properties = new RainCommuteProperties();

        properties.setHttpTimeout(Duration.ofSeconds(9));
        properties.setHttpMaxRetries(4);
        properties.setHttpRetryBackoff(Duration.ofSeconds(1));

        assertThat(properties.getHttpTimeout()).isEqualTo(Duration.ofSeconds(9));
        assertThat(properties.getHttpMaxRetries()).isEqualTo(4);
        assertThat(properties.getHttpRetryBackoff()).isEqualTo(Duration.ofSeconds(1));
    }
}

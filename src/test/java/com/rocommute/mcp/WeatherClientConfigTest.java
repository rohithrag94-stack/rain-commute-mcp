package com.rocommute.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Clock;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** Calls the {@code @Bean} factory methods directly — no Spring context needed for two one-liners. */
class WeatherClientConfigTest {

    private final WeatherClientConfig config = new WeatherClientConfig();

    @Test
    void weatherWebClient_buildsClientWithConfiguredBaseUrl() {
        WebClient webClient = config.weatherWebClient(WebClient.builder(), "https://api.open-meteo.com");

        assertThat(webClient).isNotNull();
    }

    @Test
    void clock_returnsSystemDefaultZoneClock() {
        Clock clock = config.clock();

        assertThat(clock.getZone()).isEqualTo(ZoneId.systemDefault());
    }
}

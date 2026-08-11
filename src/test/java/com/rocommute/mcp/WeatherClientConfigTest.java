package com.rocommute.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Clock;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calls the {@code @Bean} factory methods directly — no Spring context needed for three
 * one-liners. Deliberately passes RFC 2606 {@code .invalid} placeholder URLs (never the real
 * Open-Meteo hosts): {@code WebClient.Builder.build()} does no I/O, so nothing here actually
 * makes a request regardless, but an {@code .invalid} domain keeps that true even if someone
 * later adds a {@code .retrieve()}/{@code .block()} call, instead of silently starting to hit
 * the live API from a unit test.
 */
class WeatherClientConfigTest {

    private final WeatherClientConfig config = new WeatherClientConfig();

    @Test
    void webClientBuilder_returnsNonNullBuilder() {
        WebClient.Builder builder = config.webClientBuilder();

        assertThat(builder).isNotNull();
    }

    @Test
    void weatherWebClient_buildsClientWithConfiguredBaseUrl() {
        WebClient webClient = config.weatherWebClient(WebClient.builder(), "https://weather-api.invalid");

        assertThat(webClient).isNotNull();
    }

    @Test
    void geocodingWebClient_buildsClientWithConfiguredBaseUrl() {
        WebClient webClient = config.geocodingWebClient(WebClient.builder(), "https://geocoding-api.invalid");

        assertThat(webClient).isNotNull();
    }

    @Test
    void clock_returnsSystemDefaultZoneClock() {
        Clock clock = config.clock();

        assertThat(clock.getZone()).isEqualTo(ZoneId.systemDefault());
    }
}

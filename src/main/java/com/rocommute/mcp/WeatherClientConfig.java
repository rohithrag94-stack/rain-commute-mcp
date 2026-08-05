package com.rocommute.mcp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Clock;

/**
 * Provides the collaborators {@link CommuteWeatherService} and {@link GeocodingClient} depend
 * on, as beans rather than fields they build themselves — so tests can supply stub
 * {@link WebClient}s pointed at a local server and a fixed {@link Clock}, instead of hitting the
 * real APIs on real wall-clock time.
 */
@Configuration
public class WeatherClientConfig {

    /**
     * Spring Boot's {@code WebClientAutoConfiguration} only fires for a reactive web
     * application; {@code spring.main.web-application-type=none} (required for a stdio-only MCP
     * server) turns it off, so a plain builder is provided here instead.
     */
    @Bean
    WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    /**
     * @param weatherApiBaseUrl base URL of the weather forecast API; overridable via the
     *                          {@code rain-commute.weather-api.base-url} property
     */
    @Bean
    WebClient weatherWebClient(
            WebClient.Builder webClientBuilder,
            @Value("${rain-commute.weather-api.base-url:https://api.open-meteo.com}") String weatherApiBaseUrl
    ) {
        // .clone() so this bean's baseUrl doesn't leak into geocodingWebClient's build below --
        // both factory methods receive the same singleton builder instance.
        return webClientBuilder.clone().baseUrl(weatherApiBaseUrl).build();
    }

    /**
     * @param geocodingApiBaseUrl base URL of the place-name geocoding API; overridable via the
     *                            {@code rain-commute.geocoding-api.base-url} property
     */
    @Bean
    WebClient geocodingWebClient(
            WebClient.Builder webClientBuilder,
            @Value("${rain-commute.geocoding-api.base-url:https://geocoding-api.open-meteo.com}") String geocodingApiBaseUrl
    ) {
        return webClientBuilder.clone().baseUrl(geocodingApiBaseUrl).build();
    }

    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }
}

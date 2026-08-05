package com.rocommute.mcp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Clock;

/**
 * Provides the collaborators {@link CommuteWeatherService} depends on, as beans rather than
 * fields it builds itself — so tests can supply a stub {@link WebClient} pointed at a local
 * server and a fixed {@link Clock}, instead of hitting the real weather API on real wall-clock time.
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
        return webClientBuilder.baseUrl(weatherApiBaseUrl).build();
    }

    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }
}

package com.rocommute.mcp;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stubs the Open-Meteo API with an in-process {@link HttpServer} (JDK built-in, no extra test
 * dependency) and a {@link Clock#fixed} clock, so results are deterministic and independent of
 * network access or wall-clock time.
 */
class CommuteWeatherServiceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-01-01T10:15:30Z"), ZoneOffset.UTC);

    private HttpServer server;
    private volatile String responseBody;
    private CommuteWeatherService service;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/forecast", exchange -> {
            var bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        var webClient = WebClient.builder()
                .baseUrl("http://localhost:" + server.getAddress().getPort())
                .build();
        service = new CommuteWeatherService(webClient, FIXED_CLOCK);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void dryForecast_returnsDryMessage() {
        responseBody = """
                {
                  "hourly": {
                    "time": ["2026-01-01T10:00"],
                    "precipitation_probability": [20],
                    "rain": [0.0]
                  }
                }
                """;

        var result = service.checkRainOnCommute(52.37, 4.90, 30);

        assertThat(result).isEqualTo(
                "Looks dry around your arrival time (2026-01-01T10:00): only 20% chance of rain.");
    }

    @Test
    void rainyForecast_byProbability_returnsRainMessage() {
        responseBody = """
                {
                  "hourly": {
                    "time": ["2026-01-01T10:00"],
                    "precipitation_probability": [80],
                    "rain": [0.0]
                  }
                }
                """;

        var result = service.checkRainOnCommute(52.37, 4.90, 30);

        assertThat(result).isEqualTo(
                "Rain likely around your arrival time (2026-01-01T10:00): 80% chance, 0.0mm expected. Grab an umbrella.");
    }

    @Test
    void rainyForecast_byAmount_returnsRainMessage() {
        responseBody = """
                {
                  "hourly": {
                    "time": ["2026-01-01T10:00"],
                    "precipitation_probability": [10],
                    "rain": [2.5]
                  }
                }
                """;

        var result = service.checkRainOnCommute(52.37, 4.90, 30);

        assertThat(result).isEqualTo(
                "Rain likely around your arrival time (2026-01-01T10:00): 10% chance, 2.5mm expected. Grab an umbrella.");
    }

    @Test
    void missingHourlyData_returnsCouldNotRetrieveMessage() {
        responseBody = """
                {
                  "error": true,
                  "reason": "Invalid coordinates"
                }
                """;

        var result = service.checkRainOnCommute(999, 999, 30);

        assertThat(result).isEqualTo(
                "Couldn't retrieve a forecast for that location — check the coordinates.");
    }

    @Test
    void weatherApiUnreachable_returnsCouldNotRetrieveMessage() {
        server.stop(0);

        var result = service.checkRainOnCommute(52.37, 4.90, 30);

        assertThat(result).isEqualTo(
                "Couldn't retrieve a forecast for that location — check the coordinates.");
    }

    @Test
    void arrivalTimeNotCovered_returnsNoCoverageMessage() {
        responseBody = """
                {
                  "hourly": {
                    "time": ["2026-01-01T09:00"],
                    "precipitation_probability": [20],
                    "rain": [0.0]
                  }
                }
                """;

        var result = service.checkRainOnCommute(52.37, 4.90, 30);

        assertThat(result).isEqualTo(
                "Forecast doesn't cover the arrival time (2026-01-01T10:00). Try a shorter commute window.");
    }
}

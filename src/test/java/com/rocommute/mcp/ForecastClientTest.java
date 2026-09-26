package com.rocommute.mcp;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests {@link ForecastClient} against an in-process stub of Open-Meteo's forecast endpoint (the
 * same approach as {@link GeocodingClientTest}), including what it asks the API for.
 */
class ForecastClientTest {

    private static final String VALID_BODY = """
            {
              "timezone": "Asia/Kolkata",
              "hourly": {
                "time": ["2026-01-01T17:00", "2026-01-01T18:00"],
                "precipitation_probability": [20, 80],
                "rain": [0.0, 1.5],
                "temperature_2m": [24.5, 23.0],
                "wind_speed_10m": [12.0, 15.5]
              }
            }
            """;

    private HttpServer server;
    private volatile String responseBody = VALID_BODY;
    private volatile int status = 200;
    private volatile String lastRawQuery;
    private ForecastClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/forecast", exchange -> {
            lastRawQuery = exchange.getRequestURI().getRawQuery();
            var bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        var config = new WeatherClientConfig();
        var webClient = config.weatherWebClient(
                config.webClientBuilder(), "http://localhost:" + server.getAddress().getPort());
        client = new ForecastClient(webClient, TestHttp.fetcher(0));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void validResponse_isParsedIntoHourlyConditionsInTheResponsesOwnZone() {
        var forecast = client.fetch(12.97, 77.59);

        assertThat(forecast).isPresent();
        assertThat(forecast.get().zone()).isEqualTo(ZoneId.of("Asia/Kolkata"));
        assertThat(forecast.get().hours()).containsExactly(
                new Forecast.Hour(LocalDateTime.parse("2026-01-01T17:00"), 20, 0.0, 24.5, 12.0),
                new Forecast.Hour(LocalDateTime.parse("2026-01-01T18:00"), 80, 1.5, 23.0, 15.5));
    }

    /**
     * Pins what's actually requested: every field the verdicts read, two forecast days (so a late
     * arrival or a look-ahead past midnight still has buckets), and destination-local time via
     * {@code timezone=auto}, which the whole timezone-correctness guarantee rests on.
     */
    @Test
    void request_asksForAllRequiredFieldsTwoDaysAndAutoTimezone() {
        client.fetch(12.97, 77.59);

        assertThat(lastRawQuery)
                .contains("latitude=12.97")
                .contains("longitude=77.59")
                .contains("hourly=precipitation_probability,rain,temperature_2m,wind_speed_10m")
                .contains("forecast_days=2")
                .contains("timezone=auto");
    }

    @Test
    void responseWithoutHourly_isEmpty() {
        responseBody = """
                {"error": true, "reason": "Invalid coordinates"}
                """;

        assertThat(client.fetch(0, 0)).isEmpty();
    }

    @Test
    void responseWithoutTimezone_isEmpty() {
        responseBody = VALID_BODY.replace("\"timezone\": \"Asia/Kolkata\",", "");

        assertThat(client.fetch(0, 0)).isEmpty();
    }

    @Test
    void responseWithoutTimeArray_isEmpty() {
        responseBody = VALID_BODY.replace("\"time\": [\"2026-01-01T17:00\", \"2026-01-01T18:00\"],", "");

        assertThat(client.fetch(0, 0)).isEmpty();
    }

    @Test
    void responseMissingAValueArray_isEmpty() {
        responseBody = VALID_BODY.replace("\"temperature_2m\": [24.5, 23.0],", "");

        assertThat(client.fetch(0, 0)).isEmpty();
    }

    @Test
    void responseWithMismatchedArrayLengths_isEmpty() {
        responseBody = VALID_BODY.replace("\"wind_speed_10m\": [12.0, 15.5]", "\"wind_speed_10m\": [12.0]");

        assertThat(client.fetch(0, 0)).isEmpty();
    }

    @Test
    void serverError_isEmpty() {
        status = 500;

        assertThat(client.fetch(0, 0)).isEmpty();
    }

    @Test
    void unreachableApi_isEmpty() {
        server.stop(0);

        assertThat(client.fetch(0, 0)).isEmpty();
    }
}

package com.rocommute.mcp;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stubs the Open-Meteo forecast and geocoding APIs with two independent in-process
 * {@link HttpServer}s (JDK built-in, no extra test dependency) and a {@link Clock#fixed} clock,
 * so results are deterministic and independent of network access or wall-clock time. Two servers
 * (rather than one with two contexts) so "the weather API is down" and "the geocoding API is
 * down" can be tested independently of each other.
 */
class CommuteWeatherServiceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-01-01T10:15:30Z"), ZoneOffset.UTC);

    /** A successful geocoding match, reused by every test that isn't specifically about geocoding failure. */
    private static final String GEOCODING_SUCCESS_BODY = """
            {
              "results": [
                {"name": "Bengaluru", "latitude": 12.97194, "longitude": 77.59369, "country": "India"}
              ]
            }
            """;

    private HttpServer weatherServer;
    private HttpServer geocodingServer;
    private volatile String forecastResponseBody;
    private volatile String geocodingResponseBody = GEOCODING_SUCCESS_BODY;
    private CommuteWeatherService service;

    @BeforeEach
    void startServers() throws IOException {
        weatherServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        weatherServer.createContext("/v1/forecast", exchange -> respondWith(exchange, forecastResponseBody));
        weatherServer.start();

        geocodingServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        geocodingServer.createContext("/v1/search", exchange -> respondWith(exchange, geocodingResponseBody));
        geocodingServer.start();

        var config = new WeatherClientConfig();
        var sharedBuilder = config.webClientBuilder();
        var weatherWebClient = config.weatherWebClient(
                sharedBuilder, "http://localhost:" + weatherServer.getAddress().getPort());
        var geocodingWebClient = config.geocodingWebClient(
                sharedBuilder, "http://localhost:" + geocodingServer.getAddress().getPort());

        service = new CommuteWeatherService(weatherWebClient, new GeocodingClient(geocodingWebClient), FIXED_CLOCK);
    }

    private static void respondWith(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @AfterEach
    void stopServers() {
        weatherServer.stop(0);
        geocodingServer.stop(0);
    }

    @Test
    void dryForecast_returnsDryMessage() {
        forecastResponseBody = """
                {
                  "timezone": "Asia/Kolkata",
                  "hourly": {
                    "time": ["2026-01-01T16:00"],
                    "precipitation_probability": [20],
                    "rain": [0.0]
                  }
                }
                """;

        var result = service.checkRainOnCommute("Bengaluru", 30);

        assertThat(result).isEqualTo(
                "Looks dry in Bengaluru, India around your arrival time (2026-01-01T16:00): only 20% chance of rain.");
    }

    @Test
    void rainyForecast_byProbability_returnsRainMessage() {
        forecastResponseBody = """
                {
                  "timezone": "Asia/Kolkata",
                  "hourly": {
                    "time": ["2026-01-01T16:00"],
                    "precipitation_probability": [80],
                    "rain": [0.0]
                  }
                }
                """;

        var result = service.checkRainOnCommute("Bengaluru", 30);

        assertThat(result).isEqualTo(
                "Rain likely in Bengaluru, India around your arrival time (2026-01-01T16:00): 80% chance, 0.0mm expected. Grab an umbrella.");
    }

    @Test
    void rainyForecast_byAmount_returnsRainMessage() {
        forecastResponseBody = """
                {
                  "timezone": "Asia/Kolkata",
                  "hourly": {
                    "time": ["2026-01-01T16:00"],
                    "precipitation_probability": [10],
                    "rain": [2.5]
                  }
                }
                """;

        var result = service.checkRainOnCommute("Bengaluru", 30);

        assertThat(result).isEqualTo(
                "Rain likely in Bengaluru, India around your arrival time (2026-01-01T16:00): 10% chance, 2.5mm expected. Grab an umbrella.");
    }

    /**
     * The regression test for the timezone bug: the fixed clock's instant, expressed as UTC wall
     * time, would floor to "2026-01-01T10:00" -- and the old implementation, which formatted
     * arrival time in the clock's own zone instead of the destination's, would have looked for
     * exactly that string. Asia/Kolkata is UTC+5:30, so the *correct* destination-local arrival
     * hour is "2026-01-01T16:00". Only an hourly array containing that string (and not the
     * UTC-wall-clock one) should produce a Result here; if the zone handling regresses to using
     * the server's own zone, this falls through to "doesn't cover the arrival time" instead.
     */
    @Test
    void arrivalTime_isComputedInDestinationTimezone_notServerTimezone() {
        forecastResponseBody = """
                {
                  "timezone": "Asia/Kolkata",
                  "hourly": {
                    "time": ["2026-01-01T10:00", "2026-01-01T16:00"],
                    "precipitation_probability": [99, 5],
                    "rain": [0.0, 0.0]
                  }
                }
                """;

        var result = service.checkRainOnCommute("Bengaluru", 30);

        assertThat(result).isEqualTo(
                "Looks dry in Bengaluru, India around your arrival time (2026-01-01T16:00): only 5% chance of rain.");
    }

    @Test
    void destinationNotFound_returnsCouldNotFindPlaceMessage() {
        geocodingResponseBody = """
                {"generationtime_ms": 0.2}
                """;

        var result = service.checkRainOnCommute("zzzznotarealplace", 30);

        assertThat(result).isEqualTo(
                "Couldn't find a place matching \"zzzznotarealplace\" — try a more specific name.");
    }

    @Test
    void missingHourlyData_returnsCouldNotRetrieveMessage() {
        forecastResponseBody = """
                {
                  "error": true,
                  "reason": "Invalid coordinates"
                }
                """;

        var result = service.checkRainOnCommute("Bengaluru", 30);

        assertThat(result).isEqualTo("Couldn't retrieve a forecast for Bengaluru, India.");
    }

    @Test
    void missingTimezone_returnsCouldNotRetrieveMessage() {
        forecastResponseBody = """
                {
                  "hourly": {
                    "time": ["2026-01-01T16:00"],
                    "precipitation_probability": [20],
                    "rain": [0.0]
                  }
                }
                """;

        var result = service.checkRainOnCommute("Bengaluru", 30);

        assertThat(result).isEqualTo("Couldn't retrieve a forecast for Bengaluru, India.");
    }

    @Test
    void weatherApiUnreachable_returnsCouldNotRetrieveMessage() {
        weatherServer.stop(0);

        var result = service.checkRainOnCommute("Bengaluru", 30);

        assertThat(result).isEqualTo("Couldn't retrieve a forecast for Bengaluru, India.");
    }

    @Test
    void arrivalTimeNotCovered_returnsNoCoverageMessage() {
        forecastResponseBody = """
                {
                  "timezone": "Asia/Kolkata",
                  "hourly": {
                    "time": ["2026-01-01T09:00"],
                    "precipitation_probability": [20],
                    "rain": [0.0]
                  }
                }
                """;

        var result = service.checkRainOnCommute("Bengaluru", 30);

        assertThat(result).isEqualTo(
                "Forecast for Bengaluru, India doesn't cover the arrival time (2026-01-01T16:00). Try a shorter commute window.");
    }
}

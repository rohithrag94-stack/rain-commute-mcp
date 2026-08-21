package com.rocommute.mcp;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

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
    /** Captures the raw "name" query param each geocoding request actually sent, regardless of the canned response. */
    private volatile String lastGeocodingQueryName;
    private WebClient weatherWebClient;
    private GeocodingClient geocodingClient;
    private RainCommuteProperties properties;
    private CommuteWeatherService service;

    @BeforeEach
    void startServers() throws IOException {
        weatherServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        weatherServer.createContext("/v1/forecast", exchange -> respondWith(exchange, forecastResponseBody));
        weatherServer.start();

        geocodingServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        geocodingServer.createContext("/v1/search", exchange -> {
            lastGeocodingQueryName = queryParam(exchange.getRequestURI().getRawQuery(), "name");
            respondWith(exchange, geocodingResponseBody);
        });
        geocodingServer.start();

        var config = new WeatherClientConfig();
        var sharedBuilder = config.webClientBuilder();
        weatherWebClient = config.weatherWebClient(
                sharedBuilder, "http://localhost:" + weatherServer.getAddress().getPort());
        var geocodingWebClient = config.geocodingWebClient(
                sharedBuilder, "http://localhost:" + geocodingServer.getAddress().getPort());
        geocodingClient = new GeocodingClient(geocodingWebClient);
        properties = new RainCommuteProperties();

        service = new CommuteWeatherService(weatherWebClient, geocodingClient, FIXED_CLOCK, properties);
    }

    private static void respondWith(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String queryParam(String rawQuery, String key) throws UnsupportedEncodingException {
        if (rawQuery == null) {
            return null;
        }
        for (var pair : rawQuery.split("&")) {
            var parts = pair.split("=", 2);
            if (parts[0].equals(key)) {
                return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        return null;
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
                    "time": ["2026-01-01T17:00"],
                    "precipitation_probability": [20],
                    "rain": [0.0]
                  }
                }
                """;

        var result = service.checkRainOnCommute("Bengaluru", 30);

        assertThat(result).isEqualTo(
                "Looks dry in Bengaluru, India around your arrival time (2026-01-01T17:00): only 20% chance of rain.");
    }

    @Test
    void rainyForecast_byProbability_returnsRainMessage() {
        forecastResponseBody = """
                {
                  "timezone": "Asia/Kolkata",
                  "hourly": {
                    "time": ["2026-01-01T17:00"],
                    "precipitation_probability": [80],
                    "rain": [0.0]
                  }
                }
                """;

        var result = service.checkRainOnCommute("Bengaluru", 30);

        assertThat(result).isEqualTo(
                "Rain likely in Bengaluru, India around your arrival time (2026-01-01T17:00): 80% chance, 0.0mm expected. Grab an umbrella.");
    }

    @Test
    void rainyForecast_byAmount_returnsRainMessage() {
        forecastResponseBody = """
                {
                  "timezone": "Asia/Kolkata",
                  "hourly": {
                    "time": ["2026-01-01T17:00"],
                    "precipitation_probability": [10],
                    "rain": [2.5]
                  }
                }
                """;

        var result = service.checkRainOnCommute("Bengaluru", 30);

        assertThat(result).isEqualTo(
                "Rain likely in Bengaluru, India around your arrival time (2026-01-01T17:00): 10% chance, 2.5mm expected. Grab an umbrella.");
    }

    /**
     * The regression test for the timezone bug: the fixed clock's instant, expressed as UTC wall
     * time (ignoring commute-time rounding), sits in the "10:00"/"11:00" hour -- exactly what an
     * implementation that used the clock's own zone instead of the destination's would compute.
     * Asia/Kolkata is UTC+5:30, so the *correct* destination-local arrival hour is
     * "2026-01-01T17:00" (see the ceiling-semantics test below for why it's 17:00 and not 16:00).
     * Every other bucket here is seeded with a high rain probability specifically so that landing
     * on any of them -- via the wrong zone, or the wrong floor/ceiling rule -- flips the verdict
     * to "Rain likely", making a regression obvious rather than silently matching.
     */
    @Test
    void arrivalTime_isComputedInDestinationTimezone_notServerTimezone() {
        forecastResponseBody = """
                {
                  "timezone": "Asia/Kolkata",
                  "hourly": {
                    "time": ["2026-01-01T10:00", "2026-01-01T11:00", "2026-01-01T16:00", "2026-01-01T17:00"],
                    "precipitation_probability": [99, 99, 99, 5],
                    "rain": [0.0, 0.0, 0.0, 0.0]
                  }
                }
                """;

        var result = service.checkRainOnCommute("Bengaluru", 30);

        assertThat(result).isEqualTo(
                "Looks dry in Bengaluru, India around your arrival time (2026-01-01T17:00): only 5% chance of rain.");
    }

    /**
     * precipitation_probability and rain are "preceding hour" values in the Open-Meteo API (the
     * "20:00" bucket covers rain that fell *before* 20:00, not after -- verified against the live
     * docs, see AGENTS.md). So an arrival at 16:15:30 IST (10:15:30Z + 30 minutes, in Asia/Kolkata)
     * falls inside the window the *17:00* bucket describes, not 16:00 -- the target hour must be
     * rounded up, not down.
     */
    @Test
    void arrivalTime_isRoundedUpToNextHour_notFlooredDown() {
        forecastResponseBody = """
                {
                  "timezone": "Asia/Kolkata",
                  "hourly": {
                    "time": ["2026-01-01T16:00", "2026-01-01T17:00"],
                    "precipitation_probability": [99, 20],
                    "rain": [0.0, 0.0]
                  }
                }
                """;

        var result = service.checkRainOnCommute("Bengaluru", 30);

        assertThat(result).isEqualTo(
                "Looks dry in Bengaluru, India around your arrival time (2026-01-01T17:00): only 20% chance of rain.");
    }

    /**
     * The one case where rounding up would be wrong: an arrival that lands exactly on the hour is
     * already the top of its own preceding-hour window, so it must stay put rather than jump to
     * the next hour. Needs its own {@link Clock}, since {@link #FIXED_CLOCK}'s ":15:30" offset can
     * never land on an exact minute boundary no matter how many whole commute-minutes are added.
     */
    @Test
    void exactHourArrival_isNotRoundedUpToNextHour() {
        // 2026-01-01T10:30:00Z is exactly 2026-01-01T16:00:00 in Asia/Kolkata (UTC+5:30).
        var exactHourClock = Clock.fixed(Instant.parse("2026-01-01T10:30:00Z"), ZoneOffset.UTC);
        var exactHourService = new CommuteWeatherService(weatherWebClient, geocodingClient, exactHourClock, properties);
        forecastResponseBody = """
                {
                  "timezone": "Asia/Kolkata",
                  "hourly": {
                    "time": ["2026-01-01T16:00", "2026-01-01T17:00"],
                    "precipitation_probability": [20, 99],
                    "rain": [0.0, 0.0]
                  }
                }
                """;

        var result = exactHourService.checkRainOnCommute("Bengaluru", 0);

        assertThat(result).isEqualTo(
                "Looks dry in Bengaluru, India around your arrival time (2026-01-01T16:00): only 20% chance of rain.");
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
                    "time": ["2026-01-01T17:00"],
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
                "Forecast for Bengaluru, India doesn't cover the arrival time (2026-01-01T17:00). Try a shorter commute window.");
    }

    /**
     * Proves alias resolution actually rewrites the destination before geocoding runs, rather
     * than just asserting the final message looks right (which the canned geocoding stub would
     * make trivially true regardless): captures the literal "name" query param the geocoding
     * request carried and checks it's the resolved place, not the raw "home" the caller typed.
     */
    @Test
    void destinationMatchingConfiguredAlias_resolvesToAliasedPlaceName() {
        properties.setLocations(Map.of("home", "Bengaluru"));
        forecastResponseBody = """
                {
                  "timezone": "Asia/Kolkata",
                  "hourly": {
                    "time": ["2026-01-01T17:00"],
                    "precipitation_probability": [20],
                    "rain": [0.0]
                  }
                }
                """;

        var result = service.checkRainOnCommute("home", 30);

        assertThat(lastGeocodingQueryName).isEqualTo("Bengaluru");
        assertThat(result).isEqualTo(
                "Looks dry in Bengaluru, India around your arrival time (2026-01-01T17:00): only 20% chance of rain.");
    }

    /**
     * Alias matching is case-insensitive and untrimmed-whitespace-tolerant, since an LLM caller
     * relaying a user's own wording won't necessarily match the configured key's exact casing.
     */
    @Test
    void destinationMatchingAlias_isCaseInsensitive() {
        properties.setLocations(Map.of("home", "Bengaluru"));
        forecastResponseBody = """
                {
                  "timezone": "Asia/Kolkata",
                  "hourly": {
                    "time": ["2026-01-01T17:00"],
                    "precipitation_probability": [20],
                    "rain": [0.0]
                  }
                }
                """;

        service.checkRainOnCommute(" HOME ", 30);

        assertThat(lastGeocodingQueryName).isEqualTo("Bengaluru");
    }

    /**
     * Regression guard for the default-commute-minutes fallback: uses a non-default value (45,
     * not the field default of 30) so this only passes if the configured value actually got read,
     * not a hardcoded fallback baked into the code.
     */
    @Test
    void commuteMinutesOmitted_fallsBackToConfiguredDefault() {
        properties.setDefaultCommuteMinutes(45);
        // 10:15:30Z + 45min = 11:00:30 -> Asia/Kolkata 16:30:30 -> rounds up to 17:00.
        forecastResponseBody = """
                {
                  "timezone": "Asia/Kolkata",
                  "hourly": {
                    "time": ["2026-01-01T17:00"],
                    "precipitation_probability": [20],
                    "rain": [0.0]
                  }
                }
                """;

        var result = service.checkRainOnCommute("Bengaluru", null);

        assertThat(result).isEqualTo(
                "Looks dry in Bengaluru, India around your arrival time (2026-01-01T17:00): only 20% chance of rain.");
    }

    @Test
    void alternates_getSurfacedInSuccessfulVerdictMessage() {
        geocodingResponseBody = """
                {
                  "results": [
                    {"name": "Springfield", "latitude": 37.2, "longitude": -93.3, "admin1": "Missouri", "country": "United States", "population": 200000},
                    {"name": "Springfield", "latitude": 42.1, "longitude": -72.6, "admin1": "Massachusetts", "country": "United States", "population": 150000}
                  ]
                }
                """;
        forecastResponseBody = """
                {
                  "timezone": "America/Chicago",
                  "hourly": {
                    "time": ["2026-01-01T05:00"],
                    "precipitation_probability": [20],
                    "rain": [0.0]
                  }
                }
                """;

        var result = service.checkRainOnCommute("Springfield", 30);

        assertThat(result).isEqualTo(
                "Looks dry in Springfield, Missouri, United States around your arrival time (2026-01-01T05:00): "
                        + "only 20% chance of rain. (\"Springfield\" could also mean Springfield, Massachusetts, "
                        + "United States; say so if you meant one of those.)");
    }
}

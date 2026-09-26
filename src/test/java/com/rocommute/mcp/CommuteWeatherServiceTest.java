package com.rocommute.mcp;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stubs the Open-Meteo forecast and geocoding APIs with two independent in-process
 * {@link HttpServer}s (JDK built-in, no extra test dependency) and a {@link Clock#fixed} clock,
 * so results are deterministic and independent of network access or wall-clock time. Two servers
 * (rather than one with two contexts) so "the weather API is down" and "the geocoding API is
 * down" can be tested independently of each other.
 *
 * <p>The fixed clock is 10:15:30Z, which is 15:45:30 in Asia/Kolkata (UTC+5:30) -- the zone almost
 * every forecast here is in. So with a 30-minute commute the arrival is 16:15:30 IST, which rounds
 * up to the 17:00 bucket (see {@link #arrivalTime_isRoundedUpToNextHour_notFlooredDown}).
 */
class CommuteWeatherServiceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-01-01T10:15:30Z"), ZoneOffset.UTC);

    /** What every {@link #row(String, int, double)} reports for temperature and wind. */
    private static final String SKY = "24.5°C, wind 12 km/h";

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
    private ForecastClient forecastClient;
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
        var weatherWebClient = config.weatherWebClient(
                sharedBuilder, "http://localhost:" + weatherServer.getAddress().getPort());
        var geocodingWebClient = config.geocodingWebClient(
                sharedBuilder, "http://localhost:" + geocodingServer.getAddress().getPort());
        var fetcher = TestHttp.fetcher(0);
        forecastClient = new ForecastClient(weatherWebClient, fetcher);
        geocodingClient = new GeocodingClient(geocodingWebClient, fetcher, FIXED_CLOCK);
        properties = new RainCommuteProperties();

        service = new CommuteWeatherService(geocodingClient, forecastClient, FIXED_CLOCK, properties);
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

    /** One hourly bucket in a stubbed forecast. */
    private record Row(String time, int rainProbability, double rain, double temperature, double wind) {}

    private static Row row(String time, int rainProbability, double rain) {
        return new Row(time, rainProbability, rain, 24.5, 12.0);
    }

    /** Builds an Open-Meteo-shaped forecast response for {@code zone} from the given rows. */
    private static String forecastJson(String zone, Row... rows) {
        return """
                {
                  "timezone": "%s",
                  "hourly": {
                    "time": [%s],
                    "precipitation_probability": [%s],
                    "rain": [%s],
                    "temperature_2m": [%s],
                    "wind_speed_10m": [%s]
                  }
                }
                """.formatted(zone,
                join(rows, r -> "\"" + r.time() + "\""),
                join(rows, r -> String.valueOf(r.rainProbability())),
                join(rows, r -> String.valueOf(r.rain())),
                join(rows, r -> String.valueOf(r.temperature())),
                join(rows, r -> String.valueOf(r.wind())));
    }

    private static String join(Row[] rows, Function<Row, String> field) {
        return Arrays.stream(rows).map(field).collect(Collectors.joining(", "));
    }

    private static String kolkataForecast(Row... rows) {
        return forecastJson("Asia/Kolkata", rows);
    }

    /** {@code count} hourly rows from 17:00 IST on 2026-01-01, all equally rainy -- for tests about how far ahead is searched. */
    private static Row[] rainyHoursFrom1700(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> row(LocalDateTime.of(2026, 1, 1, 17, 0).plusHours(i).toString(), 90, 1.0))
                .toArray(Row[]::new);
    }

    // ---- checkRainOnCommute ----

    @Test
    void dryForecast_returnsDryMessage() {
        forecastResponseBody = kolkataForecast(row("2026-01-01T17:00", 20, 0.0));

        var result = service.checkRainOnCommute("Bengaluru", 30);

        assertThat(result).isEqualTo(
                "Looks dry in Bengaluru, India around your arrival time (2026-01-01T17:00): only 20% chance of rain, " + SKY + ".");
    }

    @Test
    void rainyForecast_byProbability_returnsRainMessage() {
        forecastResponseBody = kolkataForecast(row("2026-01-01T17:00", 80, 0.0));

        var result = service.checkRainOnCommute("Bengaluru", 30);

        assertThat(result).isEqualTo(
                "Rain likely in Bengaluru, India around your arrival time (2026-01-01T17:00): 80% chance, 0.0mm expected, "
                        + SKY + ". Grab an umbrella.");
    }

    @Test
    void rainyForecast_byAmount_returnsRainMessage() {
        forecastResponseBody = kolkataForecast(row("2026-01-01T17:00", 10, 2.5));

        var result = service.checkRainOnCommute("Bengaluru", 30);

        assertThat(result).isEqualTo(
                "Rain likely in Bengaluru, India around your arrival time (2026-01-01T17:00): 10% chance, 2.5mm expected, "
                        + SKY + ". Grab an umbrella.");
    }

    @Test
    void temperatureAndWind_areReportedForTheArrivalHour() {
        forecastResponseBody = kolkataForecast(new Row("2026-01-01T17:00", 20, 0.0, -3.26, 41.6));

        var result = service.checkRainOnCommute("Bengaluru", 30);

        assertThat(result).contains("-3.3°C, wind 42 km/h");
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
        forecastResponseBody = kolkataForecast(
                row("2026-01-01T10:00", 99, 0.0),
                row("2026-01-01T11:00", 99, 0.0),
                row("2026-01-01T16:00", 99, 0.0),
                row("2026-01-01T17:00", 5, 0.0));

        var result = service.checkRainOnCommute("Bengaluru", 30);

        assertThat(result).isEqualTo(
                "Looks dry in Bengaluru, India around your arrival time (2026-01-01T17:00): only 5% chance of rain, " + SKY + ".");
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
        forecastResponseBody = kolkataForecast(
                row("2026-01-01T16:00", 99, 0.0),
                row("2026-01-01T17:00", 20, 0.0));

        var result = service.checkRainOnCommute("Bengaluru", 30);

        assertThat(result).isEqualTo(
                "Looks dry in Bengaluru, India around your arrival time (2026-01-01T17:00): only 20% chance of rain, " + SKY + ".");
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
        var exactHourService = new CommuteWeatherService(geocodingClient, forecastClient, exactHourClock, properties);
        forecastResponseBody = kolkataForecast(
                row("2026-01-01T16:00", 20, 0.0),
                row("2026-01-01T17:00", 99, 0.0));

        var result = exactHourService.checkRainOnCommute("Bengaluru", 0);

        // The 17:00 bucket is the tripwire (99%): it must show up only as the heads-up about the
        // *following* hour, never as the verdict itself.
        assertThat(result).isEqualTo(
                "Looks dry in Bengaluru, India around your arrival time (2026-01-01T16:00): only 20% chance of rain, " + SKY
                        + ". Rain is forecast between 16:00 and 17:00 though (99% chance, 0.0mm).");
    }

    @Test
    void dryOnArrivalButRainSoonAfter_addsHeadsUp() {
        forecastResponseBody = kolkataForecast(
                row("2026-01-01T17:00", 20, 0.0),
                row("2026-01-01T18:00", 30, 0.0),
                row("2026-01-01T19:00", 70, 1.2));

        var result = service.checkRainOnCommute("Bengaluru", 30);

        assertThat(result).isEqualTo(
                "Looks dry in Bengaluru, India around your arrival time (2026-01-01T17:00): only 20% chance of rain, " + SKY
                        + ". Rain is forecast between 18:00 and 19:00 though (70% chance, 1.2mm).");
    }

    @Test
    void dryOnArrivalAndRainOnlyMuchLater_hasNoHeadsUp() {
        forecastResponseBody = kolkataForecast(
                row("2026-01-01T17:00", 20, 0.0),
                row("2026-01-01T18:00", 20, 0.0),
                row("2026-01-01T19:00", 20, 0.0),
                row("2026-01-01T20:00", 20, 0.0),
                row("2026-01-01T21:00", 90, 3.0));

        var result = service.checkRainOnCommute("Bengaluru", 30);

        assertThat(result).doesNotContain("Rain is forecast");
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
                    "rain": [0.0],
                    "temperature_2m": [24.5],
                    "wind_speed_10m": [12.0]
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
        forecastResponseBody = kolkataForecast(row("2026-01-01T09:00", 20, 0.0));

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
        forecastResponseBody = kolkataForecast(row("2026-01-01T17:00", 20, 0.0));

        var result = service.checkRainOnCommute("home", 30);

        assertThat(lastGeocodingQueryName).isEqualTo("Bengaluru");
        assertThat(result).isEqualTo(
                "Looks dry in Bengaluru, India around your arrival time (2026-01-01T17:00): only 20% chance of rain, " + SKY + ".");
    }

    /**
     * Alias matching is case-insensitive and untrimmed-whitespace-tolerant, since an LLM caller
     * relaying a user's own wording won't necessarily match the configured key's exact casing.
     */
    @Test
    void destinationMatchingAlias_isCaseInsensitive() {
        properties.setLocations(Map.of("home", "Bengaluru"));
        forecastResponseBody = kolkataForecast(row("2026-01-01T17:00", 20, 0.0));

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
        forecastResponseBody = kolkataForecast(row("2026-01-01T17:00", 20, 0.0));

        var result = service.checkRainOnCommute("Bengaluru", null);

        assertThat(result).isEqualTo(
                "Looks dry in Bengaluru, India around your arrival time (2026-01-01T17:00): only 20% chance of rain, " + SKY + ".");
    }

    @Test
    void alternates_getSurfacedInSuccessfulVerdictMessage() {
        geocodingResponseBody = SPRINGFIELD_WITH_ALTERNATE;
        forecastResponseBody = forecastJson("America/Chicago", row("2026-01-01T05:00", 20, 0.0));

        var result = service.checkRainOnCommute("Springfield", 30);

        assertThat(result).isEqualTo(
                "Looks dry in Springfield, Missouri, United States around your arrival time (2026-01-01T05:00): "
                        + "only 20% chance of rain, " + SKY + ". (\"Springfield\" could also mean Springfield, Massachusetts, "
                        + "United States; say so if you meant one of those.)");
    }

    @Test
    void alternates_getSurfacedOnRainyVerdictToo() {
        geocodingResponseBody = SPRINGFIELD_WITH_ALTERNATE;
        forecastResponseBody = forecastJson("America/Chicago", row("2026-01-01T05:00", 90, 1.0));

        var result = service.checkRainOnCommute("Springfield", 30);

        assertThat(result).endsWith("(\"Springfield\" could also mean Springfield, Massachusetts, "
                + "United States; say so if you meant one of those.)");
    }

    private static final String SPRINGFIELD_WITH_ALTERNATE = """
            {
              "results": [
                {"name": "Springfield", "latitude": 37.2, "longitude": -93.3, "admin1": "Missouri", "country": "United States", "population": 200000},
                {"name": "Springfield", "latitude": 42.1, "longitude": -72.6, "admin1": "Massachusetts", "country": "United States", "population": 150000}
              ]
            }
            """;

    // ---- suggestDepartureTime ----
    // Fixed clock: 15:45:30 IST now, so with a 30-minute commute, leaving after `w` minutes arrives at
    // 16:15:30 + w. The arrival bucket steps from 17:00 to 18:00 once w reaches 45 (arrival 17:00:30).

    @Test
    void suggestDeparture_leaveNowWhenAlreadyDry() {
        forecastResponseBody = kolkataForecast(
                row("2026-01-01T17:00", 10, 0.0),
                row("2026-01-01T18:00", 90, 2.0));

        var result = service.suggestDepartureTime("Bengaluru", 30, null);

        assertThat(result).isEqualTo(
                "Leave now: it looks dry in Bengaluru, India around your arrival time (2026-01-01T17:00): "
                        + "only 10% chance of rain, " + SKY + ".");
    }

    @Test
    void suggestDeparture_waitsForFirstDryArrivalHour() {
        forecastResponseBody = kolkataForecast(
                row("2026-01-01T17:00", 90, 2.0),
                row("2026-01-01T18:00", 5, 0.0),
                row("2026-01-01T19:00", 5, 0.0));

        var result = service.suggestDepartureTime("Bengaluru", 30, null);

        assertThat(result).isEqualTo(
                "Rain is likely in Bengaluru, India if you leave now. Leaving in about 45 minutes should get you "
                        + "there dry: arrival around 2026-01-01T18:00, only 5% chance of rain, " + SKY + ".");
    }

    @Test
    void suggestDeparture_noDryWindow_recommendsLeastRainyOption() {
        forecastResponseBody = kolkataForecast(
                row("2026-01-01T17:00", 90, 2.0),
                row("2026-01-01T18:00", 60, 0.0),
                row("2026-01-01T19:00", 70, 0.0),
                row("2026-01-01T20:00", 80, 0.0));

        var result = service.suggestDepartureTime("Bengaluru", 30, null);

        assertThat(result).isEqualTo(
                "Rain looks likely in Bengaluru, India for every departure I could check (up to 180 minutes from now). The least rainy "
                        + "option is leaving in about 45 minutes (arrival around 2026-01-01T18:00: 60% chance, 0.0mm "
                        + "expected, " + SKY + "). Grab an umbrella.");
    }

    @Test
    void suggestDeparture_noDryWindow_whenNowIsLeastRainy_saysLeaveNow() {
        forecastResponseBody = kolkataForecast(
                row("2026-01-01T17:00", 55, 0.0),
                row("2026-01-01T18:00", 90, 2.0),
                row("2026-01-01T19:00", 90, 2.0),
                row("2026-01-01T20:00", 90, 2.0));

        var result = service.suggestDepartureTime("Bengaluru", 30, null);

        assertThat(result).contains("The least rainy option is leaving now (arrival around 2026-01-01T17:00: 55% chance");
    }

    @Test
    void suggestDeparture_reportsHowFarItActuallyLooked_whenForecastEndsBeforeLookahead() {
        forecastResponseBody = kolkataForecast(
                row("2026-01-01T17:00", 90, 2.0),
                row("2026-01-01T18:00", 90, 2.0));

        var result = service.suggestDepartureTime("Bengaluru", 30, null);

        // Departing 105+ minutes from now would arrive after 18:00, in a bucket the forecast lacks.
        assertThat(result).contains("for every departure I could check (up to 90 minutes from now)");
    }

    @Test
    void suggestDeparture_forecastMissesArrivalHour_returnsNoCoverageMessage() {
        forecastResponseBody = kolkataForecast(row("2026-01-01T09:00", 20, 0.0));

        var result = service.suggestDepartureTime("Bengaluru", 30, null);

        assertThat(result).isEqualTo(
                "Forecast for Bengaluru, India doesn't cover the arrival time (2026-01-01T17:00). Try a shorter commute window.");
    }

    @Test
    void suggestDeparture_lookaheadIsCappedAtTwelveHours() {
        forecastResponseBody = kolkataForecast(rainyHoursFrom1700(30));

        var result = service.suggestDepartureTime("Bengaluru", 30, 100);

        assertThat(result).contains("for every departure I could check (up to 720 minutes from now)");
    }

    @Test
    void suggestDeparture_lookaheadHasAOneHourFloor() {
        forecastResponseBody = kolkataForecast(rainyHoursFrom1700(30));

        var result = service.suggestDepartureTime("Bengaluru", 30, 0);

        assertThat(result).contains("for every departure I could check (up to 60 minutes from now)");
    }

    @Test
    void suggestDeparture_explicitLookaheadIsHonoured() {
        forecastResponseBody = kolkataForecast(rainyHoursFrom1700(30));

        var result = service.suggestDepartureTime("Bengaluru", 30, 5);

        assertThat(result).contains("for every departure I could check (up to 300 minutes from now)");
    }

    @Test
    void suggestDeparture_resolvesAliasesAndDefaultsLikeTheRainCheck() {
        properties.setLocations(Map.of("home", "Bengaluru"));
        properties.setDefaultCommuteMinutes(30);
        forecastResponseBody = kolkataForecast(row("2026-01-01T17:00", 10, 0.0));

        var result = service.suggestDepartureTime("home", null, null);

        assertThat(lastGeocodingQueryName).isEqualTo("Bengaluru");
        assertThat(result).startsWith("Leave now:");
    }

    @Test
    void suggestDeparture_destinationNotFound_returnsCouldNotFindPlaceMessage() {
        geocodingResponseBody = """
                {"generationtime_ms": 0.2}
                """;

        var result = service.suggestDepartureTime("zzzznotarealplace", 30, null);

        assertThat(result).isEqualTo(
                "Couldn't find a place matching \"zzzznotarealplace\" — try a more specific name.");
    }

    @Test
    void suggestDeparture_weatherApiUnreachable_returnsCouldNotRetrieveMessage() {
        weatherServer.stop(0);

        var result = service.suggestDepartureTime("Bengaluru", 30, null);

        assertThat(result).isEqualTo("Couldn't retrieve a forecast for Bengaluru, India.");
    }

    @Test
    void suggestDeparture_alternatesAreSurfacedOnEveryRecommendationKind() {
        geocodingResponseBody = SPRINGFIELD_WITH_ALTERNATE;
        var alsoMeans = "(\"Springfield\" could also mean Springfield, Massachusetts, United States; "
                + "say so if you meant one of those.)";

        forecastResponseBody = forecastJson("America/Chicago", row("2026-01-01T05:00", 10, 0.0));
        assertThat(service.suggestDepartureTime("Springfield", 30, null)).endsWith(alsoMeans);

        forecastResponseBody = forecastJson("America/Chicago",
                row("2026-01-01T05:00", 90, 1.0), row("2026-01-01T06:00", 10, 0.0));
        assertThat(service.suggestDepartureTime("Springfield", 30, null)).endsWith(alsoMeans);

        forecastResponseBody = forecastJson("America/Chicago", row("2026-01-01T05:00", 90, 1.0));
        assertThat(service.suggestDepartureTime("Springfield", 30, null)).endsWith(alsoMeans);
    }
}

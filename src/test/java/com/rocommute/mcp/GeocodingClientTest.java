package com.rocommute.mcp;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stubs Open-Meteo's geocoding API with an in-process {@link HttpServer} (JDK built-in, no extra
 * test dependency), matching {@link CommuteWeatherServiceTest}'s approach. Builds the client via
 * {@link WeatherClientConfig}'s real factory methods from a single shared {@link WebClient.Builder}
 * to prove {@code .clone()} actually isolates it from a differently-configured sibling client
 * (see {@link WeatherClientConfig#weatherWebClient} javadoc) rather than just asserting in isolation.
 */
class GeocodingClientTest {

    private HttpServer server;
    private volatile String responseBody;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T10:00:00Z"));
    private GeocodingClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/search", exchange -> {
            requestCount.incrementAndGet();
            var bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        var config = new WeatherClientConfig();
        var sharedBuilder = config.webClientBuilder();
        // Deliberately build the sibling weatherWebClient first, from the same shared builder,
        // pointed at a base URL this test never talks to -- if .clone() ever regressed to
        // reusing the mutable builder directly, geocode() below would hit the wrong host/port
        // and every test in this class would fail with a connection error, not a wrong result.
        config.weatherWebClient(sharedBuilder, "http://localhost:1");
        var geocodingWebClient = config.geocodingWebClient(
                sharedBuilder, "http://localhost:" + server.getAddress().getPort());
        client = new GeocodingClient(geocodingWebClient, TestHttp.fetcher(0), clock);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void placeFound_returnsLocationWithCountryLabel() {
        responseBody = """
                {
                  "results": [
                    {"name": "Bengaluru", "latitude": 12.97194, "longitude": 77.59369, "country": "India"}
                  ]
                }
                """;

        var result = client.geocode("Bengaluru");

        assertThat(result).contains(new GeocodingClient.GeoLocation(12.97194, 77.59369, "Bengaluru, India", List.of()));
    }

    @Test
    void placeFoundWithoutCountry_returnsLocationWithNameOnlyLabel() {
        responseBody = """
                {
                  "results": [
                    {"name": "International Waters", "latitude": 0.0, "longitude": 0.0}
                  ]
                }
                """;

        var result = client.geocode("International Waters");

        assertThat(result).contains(new GeocodingClient.GeoLocation(0.0, 0.0, "International Waters", List.of()));
    }

    @Test
    void placeFoundWithAdmin1_returnsLocationWithAdminRegionInLabel() {
        responseBody = """
                {
                  "results": [
                    {"name": "Springfield", "latitude": 37.2153, "longitude": -93.2982, "admin1": "Missouri", "country": "United States"}
                  ]
                }
                """;

        var result = client.geocode("Springfield");

        assertThat(result).contains(
                new GeocodingClient.GeoLocation(37.2153, -93.2982, "Springfield, Missouri, United States", List.of()));
    }

    @Test
    void noResults_returnsEmpty() {
        responseBody = """
                {"generationtime_ms": 0.2}
                """;

        var result = client.geocode("zzzznotarealplace");

        assertThat(result).isEmpty();
    }

    @Test
    void emptyResultsArray_returnsEmpty() {
        responseBody = """
                {"results": []}
                """;

        var result = client.geocode("zzzznotarealplace");

        assertThat(result).isEmpty();
    }

    @Test
    void geocodingApiUnreachable_returnsEmpty() {
        server.stop(0);

        var result = client.geocode("Bengaluru");

        assertThat(result).isEmpty();
    }

    /**
     * Mirrors the real Open-Meteo "Springfield" response used to pick the 20% threshold and the
     * 2-alternate cap (see the constants' javadoc in GeocodingClient): four other candidates, one
     * below threshold (15%, excluded), three above it (75%, 60%, 40%), capped to the top two by
     * population rather than API response order.
     */
    @Test
    void multipleCandidatesAboveThreshold_returnsTopTwoAlternatesSortedByPopulation() {
        responseBody = """
                {
                  "results": [
                    {"name": "Springfield", "latitude": 37.2, "longitude": -93.3, "admin1": "Missouri", "country": "United States", "population": 200000},
                    {"name": "Springfield", "latitude": 39.8, "longitude": -89.6, "admin1": "Illinois", "country": "United States", "population": 120000},
                    {"name": "Springfield", "latitude": 42.1, "longitude": -72.6, "admin1": "Massachusetts", "country": "United States", "population": 150000},
                    {"name": "Springfield", "latitude": 39.9, "longitude": -83.8, "admin1": "Ohio", "country": "United States", "population": 80000},
                    {"name": "Springfield", "latitude": 36.5, "longitude": -86.9, "admin1": "Tennessee", "country": "United States", "population": 30000}
                  ]
                }
                """;

        var result = client.geocode("Springfield");

        assertThat(result).isPresent();
        assertThat(result.get().label()).isEqualTo("Springfield, Missouri, United States");
        assertThat(result.get().alternateLabels()).containsExactly(
                "Springfield, Massachusetts, United States",
                "Springfield, Illinois, United States");
    }

    /**
     * Mirrors the real Open-Meteo "Paris" response: Paris, Texas is only ~1.2% of Paris, France's
     * population, well below the 20% threshold, so it (and the other similarly small US Parises)
     * shouldn't be surfaced as an alternate.
     */
    @Test
    void candidateBelowThreshold_isNotSurfacedAsAlternate() {
        responseBody = """
                {
                  "results": [
                    {"name": "Paris", "latitude": 48.85, "longitude": 2.35, "country": "France", "population": 2138551},
                    {"name": "Paris", "latitude": 33.66, "longitude": -95.56, "admin1": "Texas", "country": "United States", "population": 24782}
                  ]
                }
                """;

        var result = client.geocode("Paris");

        assertThat(result).isPresent();
        assertThat(result.get().alternateLabels()).isEmpty();
    }

    @Test
    void primaryWithoutPopulation_returnsNoAlternatesRegardlessOfOtherCandidates() {
        responseBody = """
                {
                  "results": [
                    {"name": "Springfield", "latitude": 37.2, "longitude": -93.3, "admin1": "Missouri", "country": "United States"},
                    {"name": "Springfield", "latitude": 39.8, "longitude": -89.6, "admin1": "Illinois", "country": "United States", "population": 120000}
                  ]
                }
                """;

        var result = client.geocode("Springfield");

        assertThat(result).isPresent();
        assertThat(result.get().alternateLabels()).isEmpty();
    }

    @Test
    void candidateWithoutPopulation_isExcludedFromAlternates() {
        responseBody = """
                {
                  "results": [
                    {"name": "Springfield", "latitude": 37.2, "longitude": -93.3, "admin1": "Missouri", "country": "United States", "population": 200000},
                    {"name": "Springfield", "latitude": 39.8, "longitude": -89.6, "admin1": "Illinois", "country": "United States"}
                  ]
                }
                """;

        var result = client.geocode("Springfield");

        assertThat(result).isPresent();
        assertThat(result.get().alternateLabels()).isEmpty();
    }

    // ---- caching ----

    private static final String BENGALURU_BODY = """
            {
              "results": [
                {"name": "Bengaluru", "latitude": 12.97194, "longitude": 77.59369, "country": "India"}
              ]
            }
            """;

    @Test
    void repeatedLookup_isServedFromCacheWithoutAnotherRequest() {
        responseBody = BENGALURU_BODY;

        var first = client.geocode("Bengaluru");
        var second = client.geocode("Bengaluru");

        assertThat(second).isEqualTo(first).isPresent();
        assertThat(requestCount).hasValue(1);
    }

    @Test
    void cacheKey_ignoresCaseAndSurroundingWhitespace() {
        responseBody = BENGALURU_BODY;

        client.geocode("Bengaluru");
        var second = client.geocode("  BENGALURU ");

        assertThat(second).isPresent();
        assertThat(requestCount).hasValue(1);
    }

    /**
     * A miss must never be remembered: "no match" and "the API was briefly down" are
     * indistinguishable to GeocodingClient, and a transient outage must not stick to a name.
     */
    @Test
    void failedLookup_isNotCached() {
        responseBody = """
                {"generationtime_ms": 0.2}
                """;
        assertThat(client.geocode("Bengaluru")).isEmpty();

        responseBody = BENGALURU_BODY;
        var retry = client.geocode("Bengaluru");

        assertThat(retry).isPresent();
        assertThat(requestCount).hasValue(2);
    }

    @Test
    void cachedEntry_expiresAfterTimeToLive() {
        responseBody = BENGALURU_BODY;
        client.geocode("Bengaluru");

        clock.advance(GeocodingClient.CACHE_TIME_TO_LIVE.minusSeconds(1));
        client.geocode("Bengaluru");
        assertThat(requestCount).as("still fresh just before the TTL").hasValue(1);

        clock.advance(Duration.ofSeconds(1));
        client.geocode("Bengaluru");
        assertThat(requestCount).as("refetched once the TTL has elapsed").hasValue(2);
    }

    @Test
    void cache_evictsLeastRecentlyUsedNameOnceFull() {
        responseBody = BENGALURU_BODY;
        for (var i = 0; i <= GeocodingClient.CACHE_MAX_ENTRIES; i++) {
            client.geocode("place" + i);
        }
        assertThat(requestCount).hasValue(GeocodingClient.CACHE_MAX_ENTRIES + 1);

        client.geocode("place" + GeocodingClient.CACHE_MAX_ENTRIES);
        assertThat(requestCount).as("most recent name is still cached").hasValue(GeocodingClient.CACHE_MAX_ENTRIES + 1);

        client.geocode("place0");
        assertThat(requestCount).as("oldest name was evicted").hasValue(GeocodingClient.CACHE_MAX_ENTRIES + 2);
    }
}

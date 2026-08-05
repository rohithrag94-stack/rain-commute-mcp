package com.rocommute.mcp;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

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
    private GeocodingClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/search", exchange -> {
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
        client = new GeocodingClient(geocodingWebClient);
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

        assertThat(result).contains(new GeocodingClient.GeoLocation(12.97194, 77.59369, "Bengaluru, India"));
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

        assertThat(result).contains(new GeocodingClient.GeoLocation(0.0, 0.0, "International Waters"));
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
}

package com.rocommute.mcp;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Exercises the retry/timeout behaviour two ways: end to end against a stub server that counts
 * attempts (so "retried N times" is observed, not assumed), and the two failure classifiers
 * directly, since some of their branches (e.g. a 429, or an unrelated exception) are impractical
 * to provoke through a real socket.
 */
class ResilientJsonFetcherTest {

    private static final String OK_BODY = "{\"ok\": true}";

    private HttpServer server;
    private final AtomicInteger attempts = new AtomicInteger();
    /** Status to answer with on attempt {@code n} (1-based); attempts beyond the array reuse its last entry. */
    private volatile int[] statusByAttempt = {200};
    private volatile long delayMillis = 0;
    private WebClient webClient;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/data", exchange -> {
            var attempt = attempts.incrementAndGet();
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            var status = statusByAttempt[Math.min(attempt, statusByAttempt.length) - 1];
            var bytes = OK_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        var config = new WeatherClientConfig();
        webClient = config.weatherWebClient(
                config.webClientBuilder(), "http://localhost:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private Optional<JsonNode> fetch(ResilientJsonFetcher fetcher) {
        return fetcher.get(webClient, uriBuilder -> uriBuilder.path("/data").build());
    }

    @Test
    void successOnFirstAttempt_isReturnedWithoutRetry() {
        var result = fetch(TestHttp.fetcher(2));

        assertThat(result).isPresent();
        assertThat(result.get().get("ok").asBoolean()).isTrue();
        assertThat(attempts).hasValue(1);
    }

    @Test
    void transientServerError_isRetriedUntilItSucceeds() {
        statusByAttempt = new int[] {503, 503, 200};

        var result = fetch(TestHttp.fetcher(2));

        assertThat(result).isPresent();
        assertThat(attempts).hasValue(3);
    }

    @Test
    void persistentServerError_givesUpAfterTheConfiguredRetries() {
        statusByAttempt = new int[] {500};

        var result = fetch(TestHttp.fetcher(2));

        assertThat(result).isEmpty();
        assertThat(attempts).as("1 initial attempt + 2 retries").hasValue(3);
    }

    @Test
    void clientError_failsFastWithoutRetrying() {
        statusByAttempt = new int[] {404};

        var result = fetch(TestHttp.fetcher(2));

        assertThat(result).isEmpty();
        assertThat(attempts).hasValue(1);
    }

    @Test
    void zeroRetries_meansASingleAttempt() {
        statusByAttempt = new int[] {500};

        fetch(TestHttp.fetcher(0));

        assertThat(attempts).hasValue(1);
    }

    @Test
    void slowResponse_timesOutAndReturnsEmpty() {
        delayMillis = 600;
        var properties = TestHttp.properties(0);
        properties.setHttpTimeout(Duration.ofMillis(100));

        var result = fetch(new ResilientJsonFetcher(properties));

        assertThat(result).isEmpty();
    }

    @Test
    void timedOutAttempt_isRetried() {
        delayMillis = 600;
        var properties = TestHttp.properties(1);
        properties.setHttpTimeout(Duration.ofMillis(100));

        fetch(new ResilientJsonFetcher(properties));

        // The stub serves one request at a time, so the retry only registers once the first
        // (deliberately slow) request has finished -- wait for it rather than sleeping a guess.
        await().atMost(Duration.ofSeconds(3)).until(() -> attempts.get() >= 2);
    }

    @Test
    void unreachableServer_returnsEmpty() {
        server.stop(0);

        assertThat(fetch(TestHttp.fetcher(1))).isEmpty();
    }

    // ---- classifiers ----

    private static WebClientResponseException responseError(int status) {
        return WebClientResponseException.create(status, "status " + status, HttpHeaders.EMPTY, new byte[0], null);
    }

    @Test
    void isTransient_acceptsTimeoutsConnectionFailuresServerErrorsAndTooManyRequests() {
        assertThat(ResilientJsonFetcher.isTransient(new TimeoutException())).isTrue();
        assertThat(ResilientJsonFetcher.isTransient(
                new WebClientRequestException(new IOException("reset"), HttpMethod.GET,
                        URI.create("http://localhost/x"), HttpHeaders.EMPTY))).isTrue();
        assertThat(ResilientJsonFetcher.isTransient(responseError(HttpStatus.SERVICE_UNAVAILABLE.value()))).isTrue();
        assertThat(ResilientJsonFetcher.isTransient(responseError(HttpStatus.TOO_MANY_REQUESTS.value()))).isTrue();
    }

    @Test
    void isTransient_rejectsClientErrorsAndUnrelatedFailures() {
        assertThat(ResilientJsonFetcher.isTransient(responseError(HttpStatus.NOT_FOUND.value()))).isFalse();
        assertThat(ResilientJsonFetcher.isTransient(new IllegalStateException("bug"))).isFalse();
    }

    @Test
    void isExpectedFailure_coversWebClientErrorsAndTimeouts_butNotBugs() {
        assertThat(ResilientJsonFetcher.isExpectedFailure(responseError(500))).isTrue();
        assertThat(ResilientJsonFetcher.isExpectedFailure(new TimeoutException())).isTrue();
        assertThat(ResilientJsonFetcher.isExpectedFailure(new NullPointerException())).isFalse();
    }
}

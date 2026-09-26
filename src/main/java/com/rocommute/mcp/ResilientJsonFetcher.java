package com.rocommute.mcp;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Issues a GET and returns the JSON body, with a per-attempt timeout and a bounded retry with
 * exponential backoff for failures that are plausibly momentary (a timeout, a dropped connection,
 * a 5xx, or a 429). Both Open-Meteo clients go through this, so one flaky request doesn't turn
 * into a failed answer, while a request that is never going to work (a 4xx) fails fast instead of
 * being hammered.
 *
 * <p>Any failure that survives the retries comes back as {@link Optional#empty()} rather than an
 * exception: callers already treat "no data" as a normal, user-facing outcome.
 */
@Component
public class ResilientJsonFetcher {

    private static final int TOO_MANY_REQUESTS = 429;

    private final Duration timeout;
    private final int maxRetries;
    private final Duration retryBackoff;

    public ResilientJsonFetcher(RainCommuteProperties properties) {
        this.timeout = properties.getHttpTimeout();
        this.maxRetries = properties.getHttpMaxRetries();
        this.retryBackoff = properties.getHttpRetryBackoff();
    }

    /**
     * @param client the pre-configured (base URL) client to call
     * @param uri    builds the request URI from the client's base URL
     * @return the parsed JSON body, or empty if the request failed even after retrying
     */
    public Optional<JsonNode> get(WebClient client, Function<UriBuilder, URI> uri) {
        return client.get()
                .uri(uri)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(timeout)
                .retryWhen(Retry.backoff(maxRetries, retryBackoff)
                        .filter(ResilientJsonFetcher::isTransient)
                        // Surface the real last failure, not Reactor's generic "retries exhausted" wrapper.
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                .onErrorResume(ResilientJsonFetcher::isExpectedFailure, error -> Mono.empty())
                .blockOptional();
    }

    /** Failures worth another attempt: they say nothing about whether the request itself is valid. */
    static boolean isTransient(Throwable failure) {
        return failure instanceof TimeoutException
                || failure instanceof WebClientRequestException
                || (failure instanceof WebClientResponseException response
                        && (response.getStatusCode().is5xxServerError()
                                || response.getStatusCode().value() == TOO_MANY_REQUESTS));
    }

    /**
     * Failures that mean "the API didn't give us data" and so become an empty result. Anything
     * else (a genuine bug) is deliberately left to propagate rather than be disguised as an outage.
     */
    static boolean isExpectedFailure(Throwable failure) {
        return failure instanceof WebClientException || failure instanceof TimeoutException;
    }
}

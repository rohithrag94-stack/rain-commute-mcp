package com.rocommute.mcp;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;
import tools.jackson.databind.JsonNode;

import java.util.Optional;

/**
 * Resolves a free-text place name (e.g. "Bengaluru" or "Eiffel Tower, Paris") to coordinates via
 * Open-Meteo's free geocoding API, so {@link CommuteWeatherService} callers never have to look up
 * latitude/longitude themselves.
 */
@Component
public class GeocodingClient {

    private static final String SEARCH_PATH = "/v1/search";
    private static final String RESULTS_FIELD = "results";
    private static final String NAME_FIELD = "name";
    private static final String COUNTRY_FIELD = "country";
    private static final int RESULT_LIMIT = 1;

    private final WebClient webClient;

    public GeocodingClient(@Qualifier("geocodingWebClient") WebClient geocodingWebClient) {
        this.webClient = geocodingWebClient;
    }

    /**
     * @param placeName free-text place name or address to resolve
     * @return the best-matching location, or empty if no match was found or the API call failed
     */
    public Optional<GeoLocation> geocode(String placeName) {
        JsonNode response;
        try {
            response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(SEARCH_PATH)
                            .queryParam("name", placeName)
                            .queryParam("count", RESULT_LIMIT)
                            .queryParam("language", "en")
                            .build())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
        } catch (WebClientException e) {
            return Optional.empty();
        }

        if (!response.has(RESULTS_FIELD) || response.get(RESULTS_FIELD).isEmpty()) {
            return Optional.empty();
        }

        var firstResult = response.get(RESULTS_FIELD).get(0);
        return Optional.of(new GeoLocation(
                firstResult.get("latitude").asDouble(),
                firstResult.get("longitude").asDouble(),
                describeLocation(firstResult)));
    }

    /** Builds a human-readable "Name, Country" label so a resolved match can be confirmed back to the user. */
    private static String describeLocation(JsonNode result) {
        var name = result.get(NAME_FIELD).asString();
        return result.has(COUNTRY_FIELD) ? name + ", " + result.get(COUNTRY_FIELD).asString() : name;
    }

    /** A resolved location: coordinates plus a human-readable label for confirming the match. */
    public record GeoLocation(double latitude, double longitude, String label) {}
}

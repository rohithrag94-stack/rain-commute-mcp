package com.rocommute.mcp;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.StreamSupport;

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
    private static final String ADMIN1_FIELD = "admin1";
    private static final String COUNTRY_FIELD = "country";
    private static final String POPULATION_FIELD = "population";

    /** Open-Meteo doesn't rank strictly by population, so ask for a few candidates to consider. */
    private static final int CANDIDATE_LIMIT = 5;

    /**
     * A same-named candidate only gets mentioned as a possible alternate if its population is at
     * least this fraction of the top match's. Verified against real Open-Meteo data before
     * picking the number: the five "Springfield, US" candidates range from 59k-170k people (the
     * smallest is ~35% of the largest) -- genuinely ambiguous, all worth surfacing. "Paris"'s
     * next-biggest match after Paris, France (2.1M) is Paris, Texas at 24.8k (~1.2%) -- not worth
     * asking about. 20% sits cleanly between those two real cases.
     */
    private static final double ALTERNATE_POPULATION_RATIO_THRESHOLD = 0.20;

    /** Cap how many alternates get mentioned, so a genuinely ambiguous name doesn't produce a wall of text. */
    private static final int MAX_ALTERNATES = 2;

    /**
     * A place doesn't move, so a resolved name is good for a day -- long enough that repeat
     * questions ("home", "work", the same destination again) skip the network round trip
     * entirely, short enough that a corrected or renamed place eventually shows up.
     */
    static final Duration CACHE_TIME_TO_LIVE = Duration.ofHours(24);

    /** Bounds memory for a long-lived server process; least-recently-used names are evicted first. */
    static final int CACHE_MAX_ENTRIES = 100;

    private final WebClient webClient;
    private final ResilientJsonFetcher fetcher;
    private final ExpiringLruCache<GeoLocation> cache;

    public GeocodingClient(
            @Qualifier("geocodingWebClient") WebClient geocodingWebClient,
            ResilientJsonFetcher fetcher,
            Clock clock
    ) {
        this.webClient = geocodingWebClient;
        this.fetcher = fetcher;
        this.cache = new ExpiringLruCache<>(clock, CACHE_TIME_TO_LIVE, CACHE_MAX_ENTRIES);
    }

    /**
     * Only successful matches are cached. A miss is never remembered, because "no match" and
     * "the API was briefly unreachable" look identical from here, and a transient outage must not
     * stick to a place name for a day.
     *
     * @param placeName free-text place name or address to resolve
     * @return the best-matching location, or empty if no match was found or the API call failed
     */
    public Optional<GeoLocation> geocode(String placeName) {
        var cacheKey = placeName.trim().toLowerCase(Locale.ROOT);
        var cached = cache.get(cacheKey);
        if (cached.isPresent()) {
            return cached;
        }
        var location = lookUp(placeName);
        location.ifPresent(found -> cache.put(cacheKey, found));
        return location;
    }

    private Optional<GeoLocation> lookUp(String placeName) {
        var response = fetcher.get(webClient, uriBuilder -> uriBuilder
                .path(SEARCH_PATH)
                .queryParam("name", placeName)
                .queryParam("count", CANDIDATE_LIMIT)
                .queryParam("language", "en")
                .build());

        if (response.isEmpty() || !response.get().has(RESULTS_FIELD) || response.get().get(RESULTS_FIELD).isEmpty()) {
            return Optional.empty();
        }

        var results = response.get().get(RESULTS_FIELD);
        var primary = results.get(0);

        return Optional.of(new GeoLocation(
                primary.get("latitude").asDouble(),
                primary.get("longitude").asDouble(),
                describeLocation(primary),
                alternates(results, primary)));
    }

    /**
     * The other candidates worth mentioning, sorted most-populous first and capped at
     * {@link #MAX_ALTERNATES} -- empty if the primary match has no population figure to compare
     * against (nothing to judge prominence by, so no alternates get suggested) or if every other
     * candidate falls below {@link #ALTERNATE_POPULATION_RATIO_THRESHOLD}.
     */
    private static List<String> alternates(JsonNode results, JsonNode primary) {
        return population(primary)
                .map(primaryPopulation -> StreamSupport.stream(results.spliterator(), false)
                        .skip(1)
                        .map(candidate -> new RankedCandidate(candidate, population(candidate)))
                        .filter(c -> c.population().isPresent())
                        .filter(c -> c.population().get() >= primaryPopulation * ALTERNATE_POPULATION_RATIO_THRESHOLD)
                        .sorted(Comparator.comparingInt((RankedCandidate c) -> c.population().get()).reversed())
                        .limit(MAX_ALTERNATES)
                        .map(c -> describeLocation(c.json()))
                        .toList())
                .orElse(List.of());
    }

    private static Optional<Integer> population(JsonNode result) {
        return result.has(POPULATION_FIELD) ? Optional.of(result.get(POPULATION_FIELD).asInt()) : Optional.empty();
    }

    /** Builds a human-readable "Name, Admin region, Country" label so a resolved match can be confirmed back to the user. */
    private static String describeLocation(JsonNode result) {
        var parts = new StringBuilder(result.get(NAME_FIELD).asString());
        if (result.has(ADMIN1_FIELD)) {
            parts.append(", ").append(result.get(ADMIN1_FIELD).asString());
        }
        if (result.has(COUNTRY_FIELD)) {
            parts.append(", ").append(result.get(COUNTRY_FIELD).asString());
        }
        return parts.toString();
    }

    private record RankedCandidate(JsonNode json, Optional<Integer> population) {}

    /**
     * A resolved location: coordinates, a human-readable label for confirming the match, and any
     * other same-named places worth flagging as possible alternates (see {@link #alternates}).
     */
    public record GeoLocation(double latitude, double longitude, String label, List<String> alternateLabels) {}
}

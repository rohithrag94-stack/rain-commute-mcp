package com.rocommute.mcp;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.StreamSupport;

/**
 * Exposes the {@code checkRainOnCommute} MCP tool, which answers "will it be raining when I
 * get there" by combining a commute duration with the hourly forecast for a destination.
 */
@Service
public class CommuteWeatherService {

    private static final String FORECAST_PATH = "/v1/forecast";
    private static final String HOURLY_FIELD = "hourly";
    private static final String TIME_FIELD = "time";
    private static final String TIMEZONE_FIELD = "timezone";
    private static final String PRECIPITATION_PROBABILITY_FIELD = "precipitation_probability";
    private static final String RAIN_FIELD = "rain";
    private static final DateTimeFormatter HOUR_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    /** Open-Meteo's own "likely to rain" cutoff for precipitation probability. */
    private static final int RAIN_PROBABILITY_THRESHOLD_PERCENT = 50;

    private final WebClient webClient;
    private final GeocodingClient geocodingClient;
    private final Clock clock;

    public CommuteWeatherService(
            @Qualifier("weatherWebClient") WebClient weatherWebClient,
            GeocodingClient geocodingClient,
            Clock clock
    ) {
        this.webClient = weatherWebClient;
        this.geocodingClient = geocodingClient;
        this.clock = clock;
    }

    /**
     * Checks whether rain is expected at {@code destination} around the time you'd arrive if you
     * left right now and the commute took {@code commuteMinutes}.
     *
     * @param destination place name or address to check, e.g. "Bengaluru" or "Eiffel Tower, Paris"
     * @param commuteMinutes typical commute duration, in minutes
     * @return a human-readable verdict, or an explanation of why none could be produced
     */
    @McpTool(description = "Checks the rain forecast at a destination, at the time you'd arrive "
            + "if you left now, given a commute duration in minutes. Takes a place name or "
            + "address rather than coordinates.")
    public String checkRainOnCommute(
            @McpToolParam(description = "Destination place name or address, e.g. 'Bengaluru' or 'Eiffel Tower, Paris'")
            String destination,
            @McpToolParam(description = "Typical commute duration in minutes") int commuteMinutes
    ) {
        return switch (fetchRainOutcome(destination, commuteMinutes)) {
            case NoLocation() ->
                "Couldn't find a place matching \"%s\" — try a more specific name.".formatted(destination);
            case NoForecast(String label) ->
                "Couldn't retrieve a forecast for %s.".formatted(label);
            case NoCoverage(String label, String targetHour) ->
                "Forecast for %s doesn't cover the arrival time (%s). Try a shorter commute window."
                        .formatted(label, targetHour);
            case Result(String label, String targetHour, int rainProbability, double rainAmount)
                    when rainProbability >= RAIN_PROBABILITY_THRESHOLD_PERCENT || rainAmount > 0 ->
                "Rain likely in %s around your arrival time (%s): %d%% chance, %.1fmm expected. Grab an umbrella."
                        .formatted(label, targetHour, rainProbability, rainAmount);
            case Result(String label, String targetHour, int rainProbability, double rainAmount) ->
                "Looks dry in %s around your arrival time (%s): only %d%% chance of rain."
                        .formatted(label, targetHour, rainProbability);
        };
    }

    /**
     * Resolves the destination, fetches its hourly forecast, and reduces the two into one of
     * four outcomes: no matching place, no usable forecast (the weather API is unreachable or
     * returned no usable data), a forecast that doesn't extend as far as the arrival hour, or a
     * concrete rain/dry result for that hour.
     */
    private RainOutcome fetchRainOutcome(String destination, int commuteMinutes) {
        var location = geocodingClient.geocode(destination);
        if (location.isEmpty()) {
            return new NoLocation();
        }
        var geoLocation = location.get();

        JsonNode forecast;
        try {
            forecast = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(FORECAST_PATH)
                            .queryParam("latitude", geoLocation.latitude())
                            .queryParam("longitude", geoLocation.longitude())
                            .queryParam("hourly", PRECIPITATION_PROBABILITY_FIELD + "," + RAIN_FIELD)
                            .queryParam("forecast_days", 1)
                            .queryParam("timezone", "auto")
                            .build())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
        } catch (WebClientException e) {
            return new NoForecast(geoLocation.label());
        }

        // TIMEZONE_FIELD is required, not just nice-to-have: it's what lets the arrival time
        // below be computed in the destination's own local time rather than silently defaulting
        // to wherever this server process happens to be running. If a future API response ever
        // omits it, failing loudly here is intentional -- see fetchRainOutcome's javadoc.
        if (!forecast.has(HOURLY_FIELD) || !forecast.has(TIMEZONE_FIELD)) {
            return new NoForecast(geoLocation.label());
        }

        // Arrival is computed from the real instant (clock.instant() is timezone-agnostic), then
        // rendered in the destination's timezone -- never this server's -- because that's the
        // zone the "auto" hourly buckets below are labelled in. A user in India checking a
        // destination 30 minutes away must get a result for 30 minutes from now in IST, not in
        // whatever zone the machine running this JVM happens to be set to.
        var destinationZone = ZoneId.of(forecast.get(TIMEZONE_FIELD).asString());
        var arrivalTime = ZonedDateTime.ofInstant(
                clock.instant().plus(Duration.ofMinutes(commuteMinutes)), destinationZone);
        // Floor (not round) to the top of the arrival hour, matching the API's hourly buckets.
        var targetHour = arrivalTime
                .withMinute(0).withSecond(0).withNano(0)
                .format(HOUR_FORMAT);

        var hourly = forecast.get(HOURLY_FIELD);
        var times = StreamSupport.stream(hourly.get(TIME_FIELD).spliterator(), false)
                .map(JsonNode::asString)
                .toList();
        var idx = times.indexOf(targetHour);

        if (idx == -1) {
            return new NoCoverage(geoLocation.label(), targetHour);
        }

        var rainProbability = hourly.get(PRECIPITATION_PROBABILITY_FIELD).get(idx).asInt();
        var rainAmount = hourly.get(RAIN_FIELD).get(idx).asDouble();
        return new Result(geoLocation.label(), targetHour, rainProbability, rainAmount);
    }

    private sealed interface RainOutcome permits NoLocation, NoForecast, NoCoverage, Result {}

    /** No place matched the requested destination string. */
    private record NoLocation() implements RainOutcome {}

    /** No forecast could be obtained: the API call failed, or the response had no usable {@code hourly}/{@code timezone} data. */
    private record NoForecast(String label) implements RainOutcome {}

    /** A forecast was retrieved but doesn't extend as far as {@code targetHour}. */
    private record NoCoverage(String label, String targetHour) implements RainOutcome {}

    /** A concrete forecast for {@code targetHour}, in the destination's own local time. */
    private record Result(String label, String targetHour, int rainProbability, double rainAmount) implements RainOutcome {}
}

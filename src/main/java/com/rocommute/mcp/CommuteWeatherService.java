package com.rocommute.mcp;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.LocalDateTime;
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
    private static final String PRECIPITATION_PROBABILITY_FIELD = "precipitation_probability";
    private static final String RAIN_FIELD = "rain";
    private static final DateTimeFormatter HOUR_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    /** Open-Meteo's own "likely to rain" cutoff for precipitation probability. */
    private static final int RAIN_PROBABILITY_THRESHOLD_PERCENT = 50;

    private final WebClient webClient;
    private final Clock clock;

    public CommuteWeatherService(WebClient weatherWebClient, Clock clock) {
        this.webClient = weatherWebClient;
        this.clock = clock;
    }

    /**
     * Checks whether rain is expected at {@code (destLat, destLng)} around the time you'd
     * arrive if you left right now and the commute took {@code commuteMinutes}.
     *
     * @param destLat destination latitude
     * @param destLng destination longitude
     * @param commuteMinutes typical commute duration, in minutes
     * @return a human-readable verdict, or an explanation of why none could be produced
     */
    @McpTool(description = "Checks the rain forecast at a destination location, "
            + "at the time you'd arrive if you left now, given a commute duration in minutes.")
    public String checkRainOnCommute(
            @McpToolParam(description = "Destination latitude") double destLat,
            @McpToolParam(description = "Destination longitude") double destLng,
            @McpToolParam(description = "Typical commute duration in minutes") int commuteMinutes
    ) {
        return switch (fetchRainOutcome(destLat, destLng, commuteMinutes)) {
            case NoForecast() ->
                "Couldn't retrieve a forecast for that location — check the coordinates.";
            case NoCoverage(String targetHour) ->
                "Forecast doesn't cover the arrival time (%s). Try a shorter commute window.".formatted(targetHour);
            case Result(String targetHour, int rainProbability, double rainAmount)
                    when rainProbability >= RAIN_PROBABILITY_THRESHOLD_PERCENT || rainAmount > 0 ->
                "Rain likely around your arrival time (%s): %d%% chance, %.1fmm expected. Grab an umbrella."
                        .formatted(targetHour, rainProbability, rainAmount);
            case Result(String targetHour, int rainProbability, double rainAmount) ->
                "Looks dry around your arrival time (%s): only %d%% chance of rain.".formatted(targetHour, rainProbability);
        };
    }

    /**
     * Fetches the destination's hourly forecast and reduces it to one of three outcomes:
     * no usable forecast (bad coordinates or the weather API is unreachable), a forecast that
     * doesn't extend as far as the arrival hour, or a concrete rain/dry result for that hour.
     */
    private RainOutcome fetchRainOutcome(double destLat, double destLng, int commuteMinutes) {
        JsonNode forecast;
        try {
            forecast = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(FORECAST_PATH)
                            .queryParam("latitude", destLat)
                            .queryParam("longitude", destLng)
                            .queryParam("hourly", PRECIPITATION_PROBABILITY_FIELD + "," + RAIN_FIELD)
                            .queryParam("forecast_days", 1)
                            .queryParam("timezone", "auto")
                            .build())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
        } catch (WebClientException e) {
            return new NoForecast();
        }

        if (!forecast.has(HOURLY_FIELD)) {
            return new NoForecast();
        }

        // Floor (not round) to the top of the arrival hour, matching the API's hourly buckets.
        var arrivalTime = LocalDateTime.now(clock).plusMinutes(commuteMinutes);
        var targetHour = arrivalTime
                .withMinute(0).withSecond(0).withNano(0)
                .format(HOUR_FORMAT);

        var hourly = forecast.get(HOURLY_FIELD);
        var times = StreamSupport.stream(hourly.get(TIME_FIELD).spliterator(), false)
                .map(JsonNode::asString)
                .toList();
        var idx = times.indexOf(targetHour);

        if (idx == -1) {
            return new NoCoverage(targetHour);
        }

        var rainProbability = hourly.get(PRECIPITATION_PROBABILITY_FIELD).get(idx).asInt();
        var rainAmount = hourly.get(RAIN_FIELD).get(idx).asDouble();
        return new Result(targetHour, rainProbability, rainAmount);
    }

    private sealed interface RainOutcome permits NoForecast, NoCoverage, Result {}

    /** No forecast could be obtained: the API call failed, or the response had no {@code hourly} data. */
    private record NoForecast() implements RainOutcome {}

    /** A forecast was retrieved but doesn't extend as far as {@code targetHour}. */
    private record NoCoverage(String targetHour) implements RainOutcome {}

    /** A concrete forecast for {@code targetHour}. */
    private record Result(String targetHour, int rainProbability, double rainAmount) implements RainOutcome {}
}

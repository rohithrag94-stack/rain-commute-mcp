package com.rocommute.mcp;

import tools.jackson.databind.JsonNode;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.StreamSupport;

@Service
public class CommuteWeatherService {

    private final WebClient webClient = WebClient.builder().build();

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
                    when rainProbability >= 50 || rainAmount > 0 ->
                "Rain likely around your arrival time (%s): %d%% chance, %.1fmm expected. Grab an umbrella."
                        .formatted(targetHour, rainProbability, rainAmount);
            case Result(String targetHour, int rainProbability, double rainAmount) ->
                "Looks dry around your arrival time (%s): only %d%% chance of rain.".formatted(targetHour, rainProbability);
        };
    }

    private RainOutcome fetchRainOutcome(double destLat, double destLng, int commuteMinutes) {
        var forecast = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .scheme("https")
                        .host("api.open-meteo.com")
                        .path("/v1/forecast")
                        .queryParam("latitude", destLat)
                        .queryParam("longitude", destLng)
                        .queryParam("hourly", "precipitation_probability,rain")
                        .queryParam("forecast_days", 1)
                        .queryParam("timezone", "auto")
                        .build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        if (forecast == null || !forecast.has("hourly")) {
            return new NoForecast();
        }

        var arrivalTime = LocalDateTime.now().plusMinutes(commuteMinutes);
        var targetHour = arrivalTime
                .withMinute(0).withSecond(0).withNano(0)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));

        var hourly = forecast.get("hourly");
        var times = StreamSupport.stream(hourly.get("time").spliterator(), false)
                .map(JsonNode::asText)
                .toList();
        var idx = times.indexOf(targetHour);

        if (idx == -1) {
            return new NoCoverage(targetHour);
        }

        var rainProbability = hourly.get("precipitation_probability").get(idx).asInt();
        var rainAmount = hourly.get("rain").get(idx).asDouble();
        return new Result(targetHour, rainProbability, rainAmount);
    }

    private sealed interface RainOutcome permits NoForecast, NoCoverage, Result {}

    private record NoForecast() implements RainOutcome {}

    private record NoCoverage(String targetHour) implements RainOutcome {}

    private record Result(String targetHour, int rainProbability, double rainAmount) implements RainOutcome {}
}

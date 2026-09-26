package com.rocommute.mcp;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * Fetches and parses Open-Meteo's hourly forecast for a coordinate pair into a {@link Forecast}.
 */
@Component
public class ForecastClient {

    private static final String FORECAST_PATH = "/v1/forecast";
    private static final String HOURLY_FIELD = "hourly";
    private static final String TIME_FIELD = "time";
    private static final String TIMEZONE_FIELD = "timezone";
    private static final String PRECIPITATION_PROBABILITY_FIELD = "precipitation_probability";
    private static final String RAIN_FIELD = "rain";
    private static final String TEMPERATURE_FIELD = "temperature_2m";
    private static final String WIND_SPEED_FIELD = "wind_speed_10m";

    private static final List<String> VALUE_FIELDS = List.of(
            PRECIPITATION_PROBABILITY_FIELD, RAIN_FIELD, TEMPERATURE_FIELD, WIND_SPEED_FIELD);

    /**
     * Two days, not one: with a single day, an arrival after midnight destination-local time (a late
     * commute) has no bucket to land in, and "when should I leave" couldn't look past midnight.
     */
    private static final int FORECAST_DAYS = 2;

    private final WebClient webClient;
    private final ResilientJsonFetcher fetcher;

    public ForecastClient(@Qualifier("weatherWebClient") WebClient weatherWebClient, ResilientJsonFetcher fetcher) {
        this.webClient = weatherWebClient;
        this.fetcher = fetcher;
    }

    /**
     * @return the forecast, or empty if the API is unreachable or the response lacks any of the
     *         fields a usable forecast needs
     */
    public Optional<Forecast> fetch(double latitude, double longitude) {
        return fetcher.get(webClient, uriBuilder -> uriBuilder
                        .path(FORECAST_PATH)
                        .queryParam("latitude", latitude)
                        .queryParam("longitude", longitude)
                        .queryParam("hourly", String.join(",", VALUE_FIELDS))
                        .queryParam("forecast_days", FORECAST_DAYS)
                        .queryParam("timezone", "auto")
                        .build())
                .flatMap(ForecastClient::parse);
    }

    private static Optional<Forecast> parse(JsonNode response) {
        // TIMEZONE_FIELD is required, not just nice-to-have: it's what lets the arrival time be
        // computed in the destination's own local time rather than silently defaulting to wherever
        // this server process happens to be running (see Forecast#arrivalBucket). If a future API
        // response ever omits it, failing loudly here is intentional.
        if (!response.has(HOURLY_FIELD) || !response.has(TIMEZONE_FIELD)) {
            return Optional.empty();
        }
        var hourly = response.get(HOURLY_FIELD);
        if (!hourly.has(TIME_FIELD) || !VALUE_FIELDS.stream().allMatch(hourly::has)) {
            return Optional.empty();
        }
        var times = hourly.get(TIME_FIELD);
        if (!VALUE_FIELDS.stream().allMatch(field -> hourly.get(field).size() == times.size())) {
            return Optional.empty();
        }

        var hours = IntStream.range(0, times.size())
                .mapToObj(i -> new Forecast.Hour(
                        LocalDateTime.parse(times.get(i).asString()),
                        hourly.get(PRECIPITATION_PROBABILITY_FIELD).get(i).asInt(),
                        hourly.get(RAIN_FIELD).get(i).asDouble(),
                        hourly.get(TEMPERATURE_FIELD).get(i).asDouble(),
                        hourly.get(WIND_SPEED_FIELD).get(i).asDouble()))
                .toList();
        return Optional.of(new Forecast(ZoneId.of(response.get(TIMEZONE_FIELD).asString()), hours));
    }
}

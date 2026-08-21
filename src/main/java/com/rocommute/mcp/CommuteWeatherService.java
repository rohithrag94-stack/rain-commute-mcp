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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
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
    private final RainCommuteProperties properties;

    public CommuteWeatherService(
            @Qualifier("weatherWebClient") WebClient weatherWebClient,
            GeocodingClient geocodingClient,
            Clock clock,
            RainCommuteProperties properties
    ) {
        this.webClient = weatherWebClient;
        this.geocodingClient = geocodingClient;
        this.clock = clock;
        this.properties = properties;
    }

    /**
     * Checks whether rain is expected at {@code destination} around the time you'd arrive if you
     * left right now and the commute took {@code commuteMinutes}.
     *
     * @param destination place name, address, or a configured shortcut (e.g. "home"), to check
     * @param commuteMinutes typical commute duration, in minutes; omit to use the configured default
     * @return a human-readable verdict, or an explanation of why none could be produced
     */
    @McpTool(description = "Checks the rain forecast at a destination, at the time you'd arrive "
            + "if you left now, given a commute duration in minutes. Takes a place name or "
            + "address rather than coordinates -- also accepts the user's own configured "
            + "location shortcuts (e.g. 'home', 'work') verbatim if they mention one; pass those "
            + "words straight through rather than asking the user for a literal address. Commute "
            + "duration can be omitted to fall back to the user's configured default.")
    public String checkRainOnCommute(
            @McpToolParam(
                    description = "Destination place name or address, e.g. 'Bengaluru' or 'Eiffel Tower, Paris' -- "
                            + "or one of the user's configured shortcuts, e.g. 'home' or 'work', if they mention one",
                    required = true)
            String destination,
            @McpToolParam(
                    description = "Typical commute duration in minutes. Omit this to use the user's configured default.",
                    required = false)
            Integer commuteMinutes
    ) {
        var resolvedDestination = resolveLocationAlias(destination);
        var effectiveCommuteMinutes = commuteMinutes != null ? commuteMinutes : properties.getDefaultCommuteMinutes();

        return switch (fetchRainOutcome(resolvedDestination, effectiveCommuteMinutes)) {
            case NoLocation() ->
                "Couldn't find a place matching \"%s\" — try a more specific name.".formatted(resolvedDestination);
            case NoForecast(String label) ->
                "Couldn't retrieve a forecast for %s.".formatted(label);
            case NoCoverage(String label, String targetHour) ->
                "Forecast for %s doesn't cover the arrival time (%s). Try a shorter commute window."
                        .formatted(label, targetHour);
            case Result(String label, String targetHour, int rainProbability, double rainAmount, List<String> alternates)
                    when rainProbability >= RAIN_PROBABILITY_THRESHOLD_PERCENT || rainAmount > 0 ->
                "Rain likely in %s around your arrival time (%s): %d%% chance, %.1fmm expected. Grab an umbrella.%s"
                        .formatted(label, targetHour, rainProbability, rainAmount, alsoConsiderSuffix(resolvedDestination, alternates));
            case Result(String label, String targetHour, int rainProbability, double rainAmount, List<String> alternates) ->
                "Looks dry in %s around your arrival time (%s): only %d%% chance of rain.%s"
                        .formatted(label, targetHour, rainProbability, alsoConsiderSuffix(resolvedDestination, alternates));
        };
    }

    /**
     * If {@code destination} matches one of the user's configured location shortcuts (case
     * insensitive -- e.g. {@code rain-commute.locations.home}), substitutes the real place name
     * it points at; otherwise returns {@code destination} unchanged so plain place names keep
     * working exactly as before this feature existed.
     */
    private String resolveLocationAlias(String destination) {
        return properties.getLocations().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(destination.trim()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(destination);
    }

    /**
     * A parenthetical nudge appended to a successful verdict when the destination's name matched
     * more than one real place worth considering (see {@link GeocodingClient}) -- empty when the
     * match was unambiguous, which is the common case and leaves the message unchanged.
     */
    private static String alsoConsiderSuffix(String destination, List<String> alternateLabels) {
        if (alternateLabels.isEmpty()) {
            return "";
        }
        return " (\"%s\" could also mean %s; say so if you meant one of those.)"
                .formatted(destination, String.join(" or ", alternateLabels));
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
        // Ceiling (not floor) to the top of the arrival hour. Both PRECIPITATION_PROBABILITY_FIELD
        // and RAIN_FIELD are "preceding hour" aggregates per Open-Meteo's docs -- the bucket
        // labelled e.g. "20:00" covers the window (19:00, 20:00], i.e. rain that fell *before*
        // 20:00, not after. So an arrival at 20:59 falls in the (19:00, 20:00] window covered by
        // the *21:00* bucket, not the 20:00 one -- round up, except when arrival lands exactly on
        // the hour, which is itself the top of its own preceding-hour window.
        var truncatedToHour = arrivalTime.truncatedTo(ChronoUnit.HOURS);
        var targetHourTime = truncatedToHour.equals(arrivalTime) ? truncatedToHour : truncatedToHour.plusHours(1);
        var targetHour = targetHourTime.format(HOUR_FORMAT);

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
        return new Result(geoLocation.label(), targetHour, rainProbability, rainAmount, geoLocation.alternateLabels());
    }

    private sealed interface RainOutcome permits NoLocation, NoForecast, NoCoverage, Result {}

    /** No place matched the requested destination string. */
    private record NoLocation() implements RainOutcome {}

    /** No forecast could be obtained: the API call failed, or the response had no usable {@code hourly}/{@code timezone} data. */
    private record NoForecast(String label) implements RainOutcome {}

    /** A forecast was retrieved but doesn't extend as far as {@code targetHour}. */
    private record NoCoverage(String label, String targetHour) implements RainOutcome {}

    /** A concrete forecast for {@code targetHour}, in the destination's own local time, plus any same-named alternates worth flagging. */
    private record Result(String label, String targetHour, int rainProbability, double rainAmount, List<String> alternateLabels) implements RainOutcome {}
}

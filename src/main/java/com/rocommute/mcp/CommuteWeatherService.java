package com.rocommute.mcp;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Exposes the MCP tools: {@code checkRainOnCommute} ("will it be raining when I get there") and
 * {@code suggestDepartureTime} ("if so, when should I leave instead"). Both combine a commute
 * duration with the hourly forecast for a destination.
 */
@Service
public class CommuteWeatherService {

    private static final DateTimeFormatter HOUR_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final DateTimeFormatter CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    /** How far past the arrival hour to look for rain that's about to start. */
    private static final int RAIN_HEADS_UP_HOURS = 3;

    private static final int DEFAULT_LOOKAHEAD_HOURS = 3;
    private static final int MAX_LOOKAHEAD_HOURS = 12;
    private static final int MINUTES_PER_HOUR = 60;

    private final GeocodingClient geocodingClient;
    private final ForecastClient forecastClient;
    private final Clock clock;
    private final RainCommuteProperties properties;

    public CommuteWeatherService(
            GeocodingClient geocodingClient,
            ForecastClient forecastClient,
            Clock clock,
            RainCommuteProperties properties
    ) {
        this.geocodingClient = geocodingClient;
        this.forecastClient = forecastClient;
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
            + "if you left now, given a commute duration in minutes. Also reports temperature and "
            + "wind at that time, and warns if rain is due to start shortly after arrival. Takes a "
            + "place name or address rather than coordinates -- also accepts the user's own "
            + "configured location shortcuts (e.g. 'home', 'work') verbatim if they mention one; "
            + "pass those words straight through rather than asking the user for a literal "
            + "address. Commute duration can be omitted to fall back to the user's configured "
            + "default.")
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
        var effectiveCommuteMinutes = effectiveCommuteMinutes(commuteMinutes);

        return switch (lookUp(resolvedDestination)) {
            case Unavailable(String message) -> message;
            case Available(GeocodingClient.GeoLocation location, Forecast forecast) ->
                describeArrival(location, forecast, resolvedDestination, effectiveCommuteMinutes);
        };
    }

    /**
     * Finds the earliest departure time, within the look-ahead window, whose arrival hour is dry.
     *
     * @param destination place name, address, or a configured shortcut (e.g. "home")
     * @param commuteMinutes typical commute duration, in minutes; omit to use the configured default
     * @param lookaheadHours how many hours ahead to consider leaving; omit for 3 (capped at 12)
     * @return a human-readable recommendation, or an explanation of why none could be produced
     */
    @McpTool(description = "Answers 'when should I leave so I don't get rained on?'. Given a "
            + "destination and commute duration, checks whether leaving right now means arriving "
            + "in rain and, if so, finds the soonest departure (as a wait from now) that arrives "
            + "dry within the look-ahead window. Use this rather than checkRainOnCommute when the "
            + "user asks whether to wait, delay, or when to go. Accepts the same place names and "
            + "configured shortcuts (e.g. 'home', 'work') as checkRainOnCommute.")
    public String suggestDepartureTime(
            @McpToolParam(
                    description = "Destination place name or address, e.g. 'Bengaluru' -- or one of the "
                            + "user's configured shortcuts, e.g. 'home' or 'work', if they mention one",
                    required = true)
            String destination,
            @McpToolParam(
                    description = "Typical commute duration in minutes. Omit this to use the user's configured default.",
                    required = false)
            Integer commuteMinutes,
            @McpToolParam(
                    description = "How many hours ahead to consider leaving (default 3, at most 12).",
                    required = false)
            Integer lookaheadHours
    ) {
        var resolvedDestination = resolveLocationAlias(destination);
        var effectiveCommuteMinutes = effectiveCommuteMinutes(commuteMinutes);
        var lookaheadMinutes = Math.clamp(
                lookaheadHours != null ? lookaheadHours : DEFAULT_LOOKAHEAD_HOURS, 1, MAX_LOOKAHEAD_HOURS)
                * MINUTES_PER_HOUR;

        return switch (lookUp(resolvedDestination)) {
            case Unavailable(String message) -> message;
            case Available(GeocodingClient.GeoLocation location, Forecast forecast) -> {
                var advice = DepartureAdvisor.advise(forecast, clock.instant(), effectiveCommuteMinutes, lookaheadMinutes);
                yield describeAdvice(advice, location, resolvedDestination);
            }
        };
    }

    private int effectiveCommuteMinutes(Integer requested) {
        return requested != null ? requested : properties.getDefaultCommuteMinutes();
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
     * Resolves the destination and fetches its forecast. Either step can fail (no matching place,
     * or the weather API is unreachable / returned unusable data); that becomes a ready-to-return
     * user-facing message rather than an exception, since it's an expected outcome, not a bug.
     */
    private Lookup lookUp(String destination) {
        var location = geocodingClient.geocode(destination);
        if (location.isEmpty()) {
            return new Unavailable(
                    format("Couldn't find a place matching \"%s\" — try a more specific name.", destination));
        }
        var geoLocation = location.get();
        return forecastClient.fetch(geoLocation.latitude(), geoLocation.longitude())
                .<Lookup>map(forecast -> new Available(geoLocation, forecast))
                .orElseGet(() -> new Unavailable(format("Couldn't retrieve a forecast for %s.", geoLocation.label())));
    }

    private String describeArrival(
            GeocodingClient.GeoLocation location, Forecast forecast, String destination, int commuteMinutes
    ) {
        var arrivalBucket = forecast.arrivalBucket(clock.instant().plus(Duration.ofMinutes(commuteMinutes)));
        return forecast.at(arrivalBucket)
                .map(hour -> hour.rainy()
                        ? rainyVerdict(location, arrivalBucket, hour, destination)
                        : dryVerdict(location, forecast, arrivalBucket, hour, destination))
                .orElseGet(() -> notCovered(location, arrivalBucket));
    }

    private static String rainyVerdict(
            GeocodingClient.GeoLocation location, LocalDateTime arrivalBucket, Forecast.Hour hour, String destination
    ) {
        return format("Rain likely in %s around your arrival time (%s): %d%% chance, %.1fmm expected, %s. Grab an umbrella.%s",
                location.label(), arrivalBucket.format(HOUR_FORMAT), hour.rainProbability(), hour.rainAmount(),
                skyConditions(hour), alsoConsiderSuffix(destination, location.alternateLabels()));
    }

    private static String dryVerdict(
            GeocodingClient.GeoLocation location,
            Forecast forecast,
            LocalDateTime arrivalBucket,
            Forecast.Hour hour,
            String destination
    ) {
        return format("Looks dry in %s around your arrival time (%s): only %d%% chance of rain, %s.%s%s",
                location.label(), arrivalBucket.format(HOUR_FORMAT), hour.rainProbability(), skyConditions(hour),
                rainHeadsUp(forecast, arrivalBucket), alsoConsiderSuffix(destination, location.alternateLabels()));
    }

    /**
     * A heads-up when it's dry on arrival but rain is forecast soon after -- "dry" for the arrival
     * hour is no comfort if the return leg or the walk from the station is about to get soaked. The
     * bucket is a preceding-hour aggregate, so a rainy bucket labelled 16:00 means rain during 15:00-16:00.
     */
    private static String rainHeadsUp(Forecast forecast, LocalDateTime arrivalBucket) {
        return forecast.firstRainyAfter(arrivalBucket, RAIN_HEADS_UP_HOURS)
                .map(hour -> format(" Rain is forecast between %s and %s though (%d%% chance, %.1fmm).",
                        hour.time().minusHours(1).format(CLOCK_FORMAT), hour.time().format(CLOCK_FORMAT),
                        hour.rainProbability(), hour.rainAmount()))
                .orElse("");
    }

    private String describeAdvice(DepartureAdvisor.Advice advice, GeocodingClient.GeoLocation location, String destination) {
        var alsoConsider = alsoConsiderSuffix(destination, location.alternateLabels());
        return switch (advice) {
            case DepartureAdvisor.NotCovered(LocalDateTime arrivalBucket) -> notCovered(location, arrivalBucket);
            case DepartureAdvisor.LeaveNow(LocalDateTime arrivalBucket, Forecast.Hour hour) ->
                format("Leave now: it looks dry in %s around your arrival time (%s): only %d%% chance of rain, %s.%s",
                        location.label(), arrivalBucket.format(HOUR_FORMAT), hour.rainProbability(),
                        skyConditions(hour), alsoConsider);
            case DepartureAdvisor.WaitThenGo(int waitMinutes, LocalDateTime arrivalBucket, Forecast.Hour hour) ->
                format("Rain is likely in %s if you leave now. Leaving %s should get you there dry: arrival around %s, "
                                + "only %d%% chance of rain, %s.%s",
                        location.label(), waitPhrase(waitMinutes), arrivalBucket.format(HOUR_FORMAT),
                        hour.rainProbability(), skyConditions(hour), alsoConsider);
            case DepartureAdvisor.NoDryWindow(int checkedMinutes, int bestWaitMinutes, LocalDateTime arrivalBucket, Forecast.Hour hour) ->
                format("Rain looks likely in %s for every departure I could check (up to %d minutes from now). The least rainy option is "
                                + "leaving %s (arrival around %s: %d%% chance, %.1fmm expected, %s). Grab an umbrella.%s",
                        location.label(), checkedMinutes, waitPhrase(bestWaitMinutes), arrivalBucket.format(HOUR_FORMAT),
                        hour.rainProbability(), hour.rainAmount(), skyConditions(hour), alsoConsider);
        };
    }

    private static String waitPhrase(int waitMinutes) {
        return waitMinutes == 0 ? "now" : format("in about %d minutes", waitMinutes);
    }

    private static String notCovered(GeocodingClient.GeoLocation location, LocalDateTime arrivalBucket) {
        return format("Forecast for %s doesn't cover the arrival time (%s). Try a shorter commute window.",
                location.label(), arrivalBucket.format(HOUR_FORMAT));
    }

    private static String skyConditions(Forecast.Hour hour) {
        return format("%.1f°C, wind %.0f km/h", hour.temperatureCelsius(), hour.windSpeedKph());
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
        return format(" (\"%s\" could also mean %s; say so if you meant one of those.)",
                destination, String.join(" or ", alternateLabels));
    }

    /**
     * Locale-pinned formatting: {@code String.formatted} uses the JVM's default locale, so a
     * machine set to e.g. Dutch or German would print "2,5mm" and "18,4°C" -- message text that
     * changes with wherever the server happens to run, and makes exact-match tests host-dependent.
     */
    private static String format(String pattern, Object... args) {
        return String.format(Locale.ROOT, pattern, args);
    }

    /** The outcome of resolving a destination and fetching its forecast. */
    private sealed interface Lookup permits Unavailable, Available {}

    /** Something went wrong resolving or fetching; {@code message} explains it to the user. */
    private record Unavailable(String message) implements Lookup {}

    /** A resolved place and its forecast, ready to reason about. */
    private record Available(GeocodingClient.GeoLocation location, Forecast forecast) implements Lookup {}
}

package com.rocommute.mcp;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * An hourly forecast for one location, expressed in that location's own timezone.
 *
 * @param zone  the destination's IANA timezone -- the zone every {@link Hour#time()} is labelled in
 * @param hours the hourly buckets, in chronological order
 */
public record Forecast(ZoneId zone, List<Hour> hours) {

    /** Open-Meteo's own "likely to rain" cutoff for precipitation probability. */
    static final int RAIN_PROBABILITY_THRESHOLD_PERCENT = 50;

    /**
     * One hourly bucket. {@code rainProbability} and {@code rainAmount} are Open-Meteo
     * "preceding hour" aggregates: the bucket labelled {@code 20:00} describes 19:00-20:00.
     *
     * @param time               the bucket's label, in the forecast's own zone
     * @param rainProbability    percent chance of measurable precipitation
     * @param rainAmount         millimetres of rain
     * @param temperatureCelsius air temperature at 2 m
     * @param windSpeedKph       wind speed at 10 m, in km/h
     */
    public record Hour(
            LocalDateTime time,
            int rainProbability,
            double rainAmount,
            double temperatureCelsius,
            double windSpeedKph
    ) {

        /** Whether this bucket counts as "rain" for a verdict: a likely chance of it, or any measured amount. */
        public boolean rainy() {
            return rainProbability >= RAIN_PROBABILITY_THRESHOLD_PERCENT || rainAmount > 0;
        }
    }

    /**
     * The bucket that describes the window containing {@code arrival}.
     *
     * <p>Arrival is a real instant (timezone-agnostic), rendered in this forecast's own zone --
     * never the server's -- because that's the zone the hourly buckets are labelled in. A user in
     * India checking a destination 30 minutes away must get a result for 30 minutes from now in
     * IST, not in whatever zone the machine running this JVM happens to be set to.
     *
     * <p>Rounds up (not down) to the top of the hour. Both rain fields are "preceding hour"
     * aggregates per Open-Meteo's docs -- the bucket labelled e.g. "20:00" covers the window
     * (19:00, 20:00], i.e. rain that fell *before* 20:00, not after. So an arrival at 20:59 falls
     * in the (20:00, 21:00] window covered by the *21:00* bucket, not the 20:00 one -- round up,
     * except when arrival lands exactly on the hour, which is itself the top of its own
     * preceding-hour window.
     */
    public LocalDateTime arrivalBucket(Instant arrival) {
        var arrivalTime = arrival.atZone(zone);
        var truncatedToHour = arrivalTime.truncatedTo(ChronoUnit.HOURS);
        var bucket = truncatedToHour.equals(arrivalTime) ? truncatedToHour : truncatedToHour.plusHours(1);
        return bucket.toLocalDateTime();
    }

    /** The bucket labelled exactly {@code bucket}, or empty if the forecast doesn't reach that far. */
    public Optional<Hour> at(LocalDateTime bucket) {
        return hours.stream().filter(hour -> hour.time().equals(bucket)).findFirst();
    }

    /**
     * The first rainy bucket strictly after {@code bucket} and no more than {@code withinHours}
     * later, if any -- used to warn "it's dry when you arrive, but rain is coming."
     */
    public Optional<Hour> firstRainyAfter(LocalDateTime bucket, int withinHours) {
        var limit = bucket.plusHours(withinHours);
        return hours.stream()
                .filter(hour -> hour.time().isAfter(bucket) && !hour.time().isAfter(limit))
                .filter(Hour::rainy)
                .findFirst();
    }
}

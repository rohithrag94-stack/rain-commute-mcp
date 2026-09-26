package com.rocommute.mcp;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ForecastTest {

    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

    private static Forecast.Hour hour(String time, int probability, double rain) {
        return new Forecast.Hour(LocalDateTime.parse(time), probability, rain, 20.0, 10.0);
    }

    private static Forecast forecast(Forecast.Hour... hours) {
        return new Forecast(KOLKATA, List.of(hours));
    }

    // ---- Hour.rainy ----

    @Test
    void hour_isRainyAtTheProbabilityThreshold_notJustBelowIt() {
        assertThat(hour("2026-01-01T17:00", 49, 0.0).rainy()).isFalse();
        assertThat(hour("2026-01-01T17:00", 50, 0.0).rainy()).isTrue();
    }

    @Test
    void hour_isRainyOnAnyMeasuredAmount_evenWithLowProbability() {
        assertThat(hour("2026-01-01T17:00", 0, 0.0).rainy()).isFalse();
        assertThat(hour("2026-01-01T17:00", 0, 0.1).rainy()).isTrue();
    }

    // ---- arrivalBucket ----

    /** 10:15:30Z is 15:45:30 IST; 16:15:30 IST lies in the (16:00, 17:00] window, i.e. the 17:00 bucket. */
    @Test
    void arrivalBucket_roundsUpToTopOfHour_inTheForecastsOwnZone() {
        var arrival = Instant.parse("2026-01-01T10:45:30Z");

        assertThat(forecast().arrivalBucket(arrival)).isEqualTo(LocalDateTime.parse("2026-01-01T17:00"));
    }

    @Test
    void arrivalBucket_leavesAnExactHourAlone() {
        var arrival = Instant.parse("2026-01-01T10:30:00Z"); // exactly 16:00 IST

        assertThat(forecast().arrivalBucket(arrival)).isEqualTo(LocalDateTime.parse("2026-01-01T16:00"));
    }

    @Test
    void arrivalBucket_rollsOverMidnightInTheForecastsZone() {
        var arrival = Instant.parse("2026-01-01T18:45:00Z"); // 00:15 IST the next day

        assertThat(forecast().arrivalBucket(arrival)).isEqualTo(LocalDateTime.parse("2026-01-02T01:00"));
    }

    // ---- at ----

    @Test
    void at_findsTheExactBucket_orEmpty() {
        var seventeen = hour("2026-01-01T17:00", 10, 0.0);
        var forecast = forecast(hour("2026-01-01T16:00", 5, 0.0), seventeen);

        assertThat(forecast.at(LocalDateTime.parse("2026-01-01T17:00"))).contains(seventeen);
        assertThat(forecast.at(LocalDateTime.parse("2026-01-01T18:00"))).isEmpty();
    }

    // ---- firstRainyAfter ----

    @Test
    void firstRainyAfter_ignoresTheBucketItselfAndEarlierOnes() {
        var forecast = forecast(
                hour("2026-01-01T16:00", 90, 1.0),
                hour("2026-01-01T17:00", 90, 1.0),
                hour("2026-01-01T18:00", 10, 0.0));

        assertThat(forecast.firstRainyAfter(LocalDateTime.parse("2026-01-01T17:00"), 3)).isEmpty();
    }

    @Test
    void firstRainyAfter_returnsTheEarliestRainyBucketWithinTheWindow() {
        var early = hour("2026-01-01T19:00", 80, 0.5);
        var forecast = forecast(
                hour("2026-01-01T17:00", 10, 0.0),
                hour("2026-01-01T18:00", 10, 0.0),
                early,
                hour("2026-01-01T20:00", 95, 4.0));

        assertThat(forecast.firstRainyAfter(LocalDateTime.parse("2026-01-01T17:00"), 3)).contains(early);
    }

    @Test
    void firstRainyAfter_includesTheLastHourOfTheWindow_butNotBeyondIt() {
        var edge = hour("2026-01-01T20:00", 80, 0.5);
        var beyond = hour("2026-01-01T21:00", 80, 0.5);

        assertThat(forecast(hour("2026-01-01T17:00", 0, 0.0), edge)
                .firstRainyAfter(LocalDateTime.parse("2026-01-01T17:00"), 3)).contains(edge);
        assertThat(forecast(hour("2026-01-01T17:00", 0, 0.0), beyond)
                .firstRainyAfter(LocalDateTime.parse("2026-01-01T17:00"), 3)).isEmpty();
    }
}

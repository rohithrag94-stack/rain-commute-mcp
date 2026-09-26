package com.rocommute.mcp;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-logic tests over hand-built {@link Forecast}s (UTC, so bucket labels equal the instants
 * written here). "Now" is 12:00:00 and the commute is 30 minutes, so leaving after {@code w}
 * minutes arrives at 12:30 + w. Buckets are preceding-hour, so an arrival at exactly 13:00:00
 * still belongs to the 13:00 bucket: w = 0..30 -> 13:00, w = 45..90 -> 14:00, w = 105..150 ->
 * 15:00, w = 165 -> 16:00.
 */
class DepartureAdvisorTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final int COMMUTE = 30;

    private static Forecast.Hour hour(String time, int probability, double rain) {
        return new Forecast.Hour(LocalDateTime.parse(time), probability, rain, 15.0, 8.0);
    }

    private static Forecast forecast(Forecast.Hour... hours) {
        return new Forecast(ZoneOffset.UTC, List.of(hours));
    }

    @Test
    void dryArrivalWhenLeavingNow_isLeaveNow() {
        var dry = hour("2026-01-01T13:00", 10, 0.0);
        var forecast = forecast(dry, hour("2026-01-01T14:00", 90, 2.0));

        var advice = DepartureAdvisor.advise(forecast, NOW, COMMUTE, 180);

        assertThat(advice).isEqualTo(new DepartureAdvisor.LeaveNow(LocalDateTime.parse("2026-01-01T13:00"), dry));
    }

    @Test
    void rainWhenLeavingNow_butDryLater_waitsForTheFirstDryDeparture() {
        var dry = hour("2026-01-01T14:00", 5, 0.0);
        var forecast = forecast(hour("2026-01-01T13:00", 90, 2.0), dry, hour("2026-01-01T15:00", 5, 0.0));

        var advice = DepartureAdvisor.advise(forecast, NOW, COMMUTE, 180);

        // w=30 arrives at exactly 13:00:00, still the 13:00 bucket; w=45 arrives 13:15 -> the 14:00 bucket.
        assertThat(advice).isEqualTo(new DepartureAdvisor.WaitThenGo(45, LocalDateTime.parse("2026-01-01T14:00"), dry));
    }

    @Test
    void neverDry_recommendsTheLeastRainyOption_andReportsHowFarItChecked() {
        var leastRainy = hour("2026-01-01T14:00", 60, 0.0);
        var forecast = forecast(
                hour("2026-01-01T13:00", 90, 2.0),
                leastRainy,
                hour("2026-01-01T15:00", 70, 0.0));

        var advice = DepartureAdvisor.advise(forecast, NOW, COMMUTE, 180);

        // The forecast ends at the 15:00 bucket: w=150 arrives at exactly 15:00 (last covered), w=165 needs 16:00.
        assertThat(advice).isEqualTo(
                new DepartureAdvisor.NoDryWindow(150, 45, LocalDateTime.parse("2026-01-01T14:00"), leastRainy));
    }

    @Test
    void lookaheadShorterThanForecast_limitsWhatIsChecked() {
        var forecast = forecast(
                hour("2026-01-01T13:00", 90, 2.0),
                hour("2026-01-01T14:00", 90, 2.0),
                hour("2026-01-01T15:00", 90, 2.0),
                hour("2026-01-01T16:00", 90, 2.0));

        var advice = DepartureAdvisor.advise(forecast, NOW, COMMUTE, 60);

        assertThat(advice).isInstanceOfSatisfying(DepartureAdvisor.NoDryWindow.class,
                noDry -> assertThat(noDry.checkedMinutes()).isEqualTo(60));
    }

    @Test
    void equalProbability_prefersLessRain_thenTheEarlierDeparture() {
        var drizzlier = hour("2026-01-01T13:00", 60, 1.0);
        var lessRain = hour("2026-01-01T14:00", 60, 0.2);
        var sameAsLessRain = hour("2026-01-01T15:00", 60, 0.2);

        var advice = DepartureAdvisor.advise(forecast(drizzlier, lessRain, sameAsLessRain), NOW, COMMUTE, 180);

        assertThat(advice).isInstanceOfSatisfying(DepartureAdvisor.NoDryWindow.class, noDry -> {
            assertThat(noDry.conditions()).isEqualTo(lessRain);
            assertThat(noDry.bestWaitMinutes()).isEqualTo(45);
        });
    }

    @Test
    void forecastNotReachingTheImmediateArrivalHour_isNotCovered() {
        var forecast = forecast(hour("2026-01-01T09:00", 10, 0.0));

        var advice = DepartureAdvisor.advise(forecast, NOW, COMMUTE, 180);

        assertThat(advice).isEqualTo(new DepartureAdvisor.NotCovered(LocalDateTime.parse("2026-01-01T13:00")));
    }
}

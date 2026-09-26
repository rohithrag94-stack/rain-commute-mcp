package com.rocommute.mcp;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * Answers "if it's going to rain when I arrive, should I leave later?" by trying departure times
 * at fixed steps from now and finding the earliest one whose arrival lands in a dry hour. Pure
 * logic over an already-fetched {@link Forecast}: no I/O and no wall clock, so it's exercised
 * directly in tests.
 *
 * <p>Departure times are reported as a wait from now, never a clock time: the user's own timezone
 * may differ from the destination's, and only the destination's is known here.
 */
final class DepartureAdvisor {

    /** How finely departure times are tried. Hourly forecast buckets make anything finer pointless. */
    static final int STEP_MINUTES = 15;

    private DepartureAdvisor() {}

    sealed interface Advice permits LeaveNow, WaitThenGo, NoDryWindow, NotCovered {}

    /** Leaving right now already arrives in a dry hour. */
    record LeaveNow(LocalDateTime arrivalBucket, Forecast.Hour conditions) implements Advice {}

    /** Leaving now arrives in rain, but leaving {@code waitMinutes} from now arrives dry. */
    record WaitThenGo(int waitMinutes, LocalDateTime arrivalBucket, Forecast.Hour conditions) implements Advice {}

    /**
     * Every departure checked arrives in rain. Carries the least-rainy option and how far ahead
     * was actually checked (the forecast can end before the requested look-ahead does).
     */
    record NoDryWindow(int checkedMinutes, int bestWaitMinutes, LocalDateTime arrivalBucket, Forecast.Hour conditions)
            implements Advice {}

    /** The forecast doesn't reach even the arrival hour for leaving right now. */
    record NotCovered(LocalDateTime arrivalBucket) implements Advice {}

    private record Candidate(int waitMinutes, LocalDateTime arrivalBucket, Forecast.Hour conditions) {}

    /**
     * @param forecast         the destination's hourly forecast
     * @param now              the current instant
     * @param commuteMinutes   how long the trip takes
     * @param lookaheadMinutes how far ahead to consider leaving
     */
    static Advice advise(Forecast forecast, Instant now, int commuteMinutes, int lookaheadMinutes) {
        // takeWhile: stop at the first departure whose arrival hour the forecast doesn't reach --
        // every later one is further out and can't be covered either.
        var candidates = IntStream.rangeClosed(0, lookaheadMinutes / STEP_MINUTES)
                .map(step -> step * STEP_MINUTES)
                .mapToObj(wait -> candidate(forecast, now, commuteMinutes, wait))
                .takeWhile(Optional::isPresent)
                .map(Optional::get)
                .toList();

        if (candidates.isEmpty()) {
            return new NotCovered(forecast.arrivalBucket(now.plus(Duration.ofMinutes(commuteMinutes))));
        }

        var leaveNow = candidates.getFirst();
        if (!leaveNow.conditions().rainy()) {
            return new LeaveNow(leaveNow.arrivalBucket(), leaveNow.conditions());
        }

        return candidates.stream()
                .filter(candidate -> !candidate.conditions().rainy())
                .findFirst()
                .<Advice>map(dry -> new WaitThenGo(dry.waitMinutes(), dry.arrivalBucket(), dry.conditions()))
                .orElseGet(() -> noDryWindow(candidates));
    }

    private static Optional<Candidate> candidate(Forecast forecast, Instant now, int commuteMinutes, int waitMinutes) {
        var arrival = now.plus(Duration.ofMinutes((long) waitMinutes + commuteMinutes));
        var bucket = forecast.arrivalBucket(arrival);
        return forecast.at(bucket).map(conditions -> new Candidate(waitMinutes, bucket, conditions));
    }

    /** Ties go to the earlier departure: no reason to make someone wait for an equally rainy hour. */
    private static NoDryWindow noDryWindow(List<Candidate> candidates) {
        var best = candidates.stream()
                .min(Comparator.comparingInt((Candidate c) -> c.conditions().rainProbability())
                        .thenComparingDouble(c -> c.conditions().rainAmount()))
                .orElseThrow();
        return new NoDryWindow(
                candidates.getLast().waitMinutes(), best.waitMinutes(), best.arrivalBucket(), best.conditions());
    }
}

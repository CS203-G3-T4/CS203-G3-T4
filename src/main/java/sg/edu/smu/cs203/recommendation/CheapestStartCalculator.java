package sg.edu.smu.cs203.recommendation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import sg.edu.smu.cs203.household.appliance.TimeWindow;

/**
 * CSDT4-19: for one flexible appliance, try every half-hour start that fits its window, pick
 * the cheapest, and compare it with the appliance's usual start.
 *
 * Cost of a run = sum over the half-hours it touches of
 *     power (kW) x hours spent in that half-hour x forecast USEP (SGD/MWh) / 1000   -> SGD
 *
 * Which run: the next occurrence of the appliance's window whose usual start is still ahead
 * (not before now, and not before the first forecast half-hour). A suggestion must start on a
 * :00/:30 boundary, not before now, not before the window opens, and must finish by the
 * window's end.
 *
 * CSDT4-38: if the usual run touches a half-hour the forecast marks as a spike, the outcome is
 * AVOID instead, with the cheapest start that touches no spike as the alternative and the cost
 * avoided as the saving.
 * No Spring and no system clock here: the caller passes "now", so tests are exact.
 */
public final class CheapestStartCalculator {

    static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
    private static final long HALF_HOUR_SECONDS = 1800;
    private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3600);
    private static final BigDecimal KW_MWH_FACTOR = BigDecimal.valueOf(1000);

    private CheapestStartCalculator() {
    }

    /** What we know about the appliance. usualStart may be null (then the window's start is used). */
    public record ShiftableRun(BigDecimal powerKw, int runMinutes, LocalTime earliestStart,
                               LocalTime mustFinishBy, LocalTime usualStart) {
    }

    public enum Outcome {
        /** A cheaper start exists; suggest it. */
        SHIFT,
        /** A price spike is forecast during the usual run; warn, with a spike-free alternative. */
        AVOID,
        /** The usual start is already the cheapest (or saves less than the minimum). */
        USUAL_IS_CHEAPEST,
        /** The forecast doesn't cover the next usual run, so nothing can be compared. */
        NOT_ENOUGH_FORECAST
    }

    /**
     * Costs are in SGD for one run, rounded to 4 decimal places. Fields are null when not known.
     * spikeStart is the first spike half-hour the usual run touches (AVOID only).
     */
    public record Result(Outcome outcome, Instant usualStart, BigDecimal usualCost,
                         Instant bestStart, BigDecimal bestCost, BigDecimal saving, Instant spikeStart) {

        public Result(Outcome outcome, Instant usualStart, BigDecimal usualCost,
                      Instant bestStart, BigDecimal bestCost, BigDecimal saving) {
            this(outcome, usualStart, usualCost, bestStart, bestCost, saving, null);
        }
    }

    public static Result cheapestStart(ShiftableRun run, List<PriceForecast.HalfHourPrice> forecast,
                                       Instant now, BigDecimal minimumSaving) {
        Map<Instant, BigDecimal> prices = new HashMap<>();
        Set<Instant> spikes = new HashSet<>();
        Instant firstPeriod = null;
        for (PriceForecast.HalfHourPrice point : forecast) {
            prices.put(point.periodStart(), point.usepSgdPerMwh());
            if (point.spike()) {
                spikes.add(point.periodStart());
            }
            if (firstPeriod == null || point.periodStart().isBefore(firstPeriod)) {
                firstPeriod = point.periodStart();
            }
        }
        if (firstPeriod == null) {
            return notEnoughForecast(null);
        }

        TimeWindow window = TimeWindow.of(run.earliestStart(), run.mustFinishBy());
        // A usual run that has already started is not "ahead", even if the forecast (made a
        // little earlier) still has a price for its half-hour.
        Instant notBefore = now.isAfter(firstPeriod) ? now : firstPeriod;
        Occurrence occurrence = nextOccurrence(run, window, notBefore);
        BigDecimal usualCost = costOf(occurrence.usualStart(), run, prices);
        if (usualCost == null) {
            return notEnoughForecast(occurrence.usualStart());
        }

        BigDecimal usual = round(usualCost);

        Instant spikeStart = firstSpikeTouched(occurrence.usualStart(), run, spikes);
        if (spikeStart != null) {
            Best alternative = best(run, prices, occurrence, now,
                    start -> firstSpikeTouched(start, run, spikes) == null);
            if (alternative != null) {
                BigDecimal saving = round(usualCost.subtract(alternative.cost()));
                if (saving.signum() > 0 && saving.compareTo(minimumSaving) >= 0) {
                    return new Result(Outcome.AVOID, occurrence.usualStart(), usual, alternative.start(),
                            round(alternative.cost()), saving, spikeStart);
                }
            }
            // No spike-free start in the window saves enough: fall back to the ordinary comparison.
        }

        Best cheapest = best(run, prices, occurrence, now, start -> true);
        Instant bestStart = cheapest == null ? null : cheapest.start();
        BigDecimal bestCost = cheapest == null ? null : cheapest.cost();

        if (bestStart == null || bestStart.equals(occurrence.usualStart())) {
            return new Result(Outcome.USUAL_IS_CHEAPEST, occurrence.usualStart(), usual, null, null, null);
        }
        BigDecimal best = round(bestCost);
        BigDecimal saving = round(usualCost.subtract(bestCost));
        if (saving.signum() <= 0 || saving.compareTo(minimumSaving) < 0) {
            return new Result(Outcome.USUAL_IS_CHEAPEST, occurrence.usualStart(), usual, bestStart, best, saving);
        }
        return new Result(Outcome.SHIFT, occurrence.usualStart(), usual, bestStart, best, saving);
    }

    private record Best(Instant start, BigDecimal cost) {
    }

    /** The cheapest allowed start inside this occurrence of the window, not before now. */
    private static Best best(ShiftableRun run, Map<Instant, BigDecimal> prices, Occurrence occurrence,
                             Instant now, Predicate<Instant> allowed) {
        Best best = null;
        for (Instant start : prices.keySet().stream().sorted().toList()) {
            Instant finish = start.plus(Duration.ofMinutes(run.runMinutes()));
            if (start.isBefore(now) || start.isBefore(occurrence.opens()) || finish.isAfter(occurrence.closes())
                    || !allowed.test(start)) {
                continue;
            }
            BigDecimal cost = costOf(start, run, prices);
            // Strictly cheaper only, so a tie keeps the earlier start.
            if (cost != null && (best == null || cost.compareTo(best.cost()) < 0)) {
                best = new Best(start, cost);
            }
        }
        return best;
    }

    /** The first half-hour a run from start touches that is a forecast spike, or null. */
    static Instant firstSpikeTouched(Instant start, ShiftableRun run, Set<Instant> spikes) {
        if (spikes.isEmpty()) {
            return null;
        }
        Instant end = start.plus(Duration.ofMinutes(run.runMinutes()));
        Instant period = Instant.ofEpochSecond(
                Math.floorDiv(start.getEpochSecond(), HALF_HOUR_SECONDS) * HALF_HOUR_SECONDS);
        while (period.isBefore(end)) {
            if (spikes.contains(period)) {
                return period;
            }
            period = period.plusSeconds(HALF_HOUR_SECONDS);
        }
        return null;
    }

    /**
     * Cost in SGD of running from start for runMinutes, or null if a half-hour it touches has no
     * forecast price. Handles runs that start or end part-way through a half-hour (e.g. 19:45).
     */
    static BigDecimal costOf(Instant start, ShiftableRun run, Map<Instant, BigDecimal> prices) {
        Instant end = start.plus(Duration.ofMinutes(run.runMinutes()));
        BigDecimal total = BigDecimal.ZERO;
        Instant cursor = start;
        while (cursor.isBefore(end)) {
            Instant periodStart = Instant.ofEpochSecond(
                    Math.floorDiv(cursor.getEpochSecond(), HALF_HOUR_SECONDS) * HALF_HOUR_SECONDS);
            Instant periodEnd = periodStart.plusSeconds(HALF_HOUR_SECONDS);
            Instant sliceEnd = end.isBefore(periodEnd) ? end : periodEnd;
            BigDecimal price = prices.get(periodStart);
            if (price == null) {
                return null;
            }
            BigDecimal hours = BigDecimal.valueOf(Duration.between(cursor, sliceEnd).getSeconds())
                    .divide(SECONDS_PER_HOUR, 10, RoundingMode.HALF_UP);
            total = total.add(run.powerKw().multiply(hours).multiply(price)
                    .divide(KW_MWH_FACTOR, 10, RoundingMode.HALF_UP));
            cursor = sliceEnd;
        }
        return total;
    }

    /** One day's window, e.g. 07:00 -> 21:00 on 30 Sep, with that day's usual start. */
    record Occurrence(Instant opens, Instant closes, Instant usualStart) {
    }

    /**
     * The first occurrence of the window whose usual start is on or after notBefore (the later of
     * now and the first forecast half-hour). Checks yesterday's window too, for windows that
     * cross midnight (22:00 -> 07:00).
     */
    static Occurrence nextOccurrence(ShiftableRun run, TimeWindow window, Instant notBefore) {
        int length = window.lengthMinutes();
        int usualOffset = 0;
        if (run.usualStart() != null) {
            usualOffset = minutesAfter(run.earliestStart(), run.usualStart());
            // F2 already checks this; fall back to the window's start if the data is inconsistent.
            if (usualOffset + run.runMinutes() > length) {
                usualOffset = 0;
            }
        }
        LocalDate date = notBefore.atZone(SINGAPORE).toLocalDate().minusDays(1);
        while (true) {
            Instant opens = ZonedDateTime.of(date, run.earliestStart(), SINGAPORE).toInstant();
            Instant usual = opens.plus(Duration.ofMinutes(usualOffset));
            if (!usual.isBefore(notBefore)) {
                return new Occurrence(opens, opens.plus(Duration.ofMinutes(length)), usual);
            }
            date = date.plusDays(1);
        }
    }

    /**
     * CSDT4-20 override: the instant at local time `wanted` inside the same window occurrence as
     * `suggestedStart`, or null if a run starting then would not finish inside the window.
     */
    public static Instant startInSameWindow(ShiftableRun run, Instant suggestedStart, LocalTime wanted) {
        TimeWindow window = TimeWindow.of(run.earliestStart(), run.mustFinishBy());
        int length = window.lengthMinutes();
        LocalDate date = suggestedStart.atZone(SINGAPORE).toLocalDate();
        for (LocalDate candidate : List.of(date.minusDays(1), date)) {
            Instant opens = ZonedDateTime.of(candidate, run.earliestStart(), SINGAPORE).toInstant();
            Instant closes = opens.plus(Duration.ofMinutes(length));
            if (!suggestedStart.isBefore(opens) && suggestedStart.isBefore(closes)) {
                int offset = minutesAfter(run.earliestStart(), wanted);
                if (offset + run.runMinutes() > length) {
                    return null;
                }
                return opens.plus(Duration.ofMinutes(offset));
            }
        }
        return null;
    }

    private static int minutesAfter(LocalTime from, LocalTime to) {
        int minutes = (to.toSecondOfDay() - from.toSecondOfDay()) / 60;
        return minutes < 0 ? minutes + 24 * 60 : minutes;
    }

    private static Result notEnoughForecast(Instant usualStart) {
        return new Result(Outcome.NOT_ENOUGH_FORECAST, usualStart, null, null, null, null);
    }

    private static BigDecimal round(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }
}

package sg.edu.smu.cs203.recommendation;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * CSDT4-39 confidence, option A: on each of the last 28 days, would this run really have cost
 * less at the suggested time than at the usual time, using actual prices? The same times of day
 * are compared (the same gap between them, so windows across midnight work too).
 *
 * A day counts only if every half-hour both runs touch has an actual price, known by asOf. With
 * fewer than 14 such days there is no confidence ("not enough price history yet"), never a guess.
 * Next sprint (option B) can add "how often F3's forecast was right" once F3 has saved weeks of
 * forecasts.
 */
public final class BacktestConfidence {

    static final int DAYS = 28;
    static final int MINIMUM_DAYS = 14;

    private BacktestConfidence() {
    }

    /** cheaperDays out of daysChecked; percent rounded to a whole number. */
    public record Confidence(int cheaperDays, int daysChecked) {

        public Confidence {
            if (daysChecked < 1 || cheaperDays < 0 || cheaperDays > daysChecked) {
                throw new IllegalArgumentException("Invalid confidence " + cheaperDays + "/" + daysChecked);
            }
        }

        public int percent() {
            return (int) Math.round(100.0 * cheaperDays / daysChecked);
        }
    }

    /**
     * @return the confidence, or null when fewer than 14 of the 28 days can be checked
     */
    public static Confidence of(CheapestStartCalculator.ShiftableRun run, Instant usualStart, Instant suggestedStart,
                                Map<Instant, BigDecimal> actualPrices, Instant asOf) {
        Duration length = Duration.ofMinutes(run.runMinutes());
        int checked = 0;
        int cheaper = 0;
        for (int day = 1; day <= DAYS; day++) {
            Instant usual = usualStart.minus(Duration.ofDays(day));
            Instant suggested = suggestedStart.minus(Duration.ofDays(day));
            if (usual.plus(length).isAfter(asOf) || suggested.plus(length).isAfter(asOf)) {
                continue; // that day's run hadn't finished yet, so its actual cost isn't known
            }
            BigDecimal usualCost = CheapestStartCalculator.costOf(usual, run, actualPrices);
            BigDecimal suggestedCost = CheapestStartCalculator.costOf(suggested, run, actualPrices);
            if (usualCost == null || suggestedCost == null) {
                continue; // a missing price: skip the day rather than guess
            }
            checked++;
            if (suggestedCost.compareTo(usualCost) < 0) {
                cheaper++;
            }
        }
        return checked < MINIMUM_DAYS ? null : new Confidence(cheaper, checked);
    }
}

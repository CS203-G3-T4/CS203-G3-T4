package sg.edu.smu.cs203.recommendation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * CSDT4-39: explains a suggestion with the numbers behind it, e.g.
 *
 *   "Prices are forecast to fall from 31¢ to 20¢/kWh by 10:00 PM. Starting at 10:00 PM still
 *    finishes by 11:00 PM, before your 11:30 PM limit. Estimated saving: $0.55 on this run
 *    ($1.00 vs $1.55)."
 *
 * Prices are the forecast average over each run in cents per kWh (what residents see on a bill;
 * 1 SGD/MWh = 0.1¢/kWh), worked out from the run's forecast cost and energy. Times are Singapore
 * time. The saving is always called "estimated": it comes from the forecast, not actual prices.
 */
public final class RecommendationExplanation {

    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    private static final BigDecimal TEN = BigDecimal.TEN;

    private RecommendationExplanation() {
    }

    public static String explain(CheapestStartCalculator.Result result, CheapestStartCalculator.ShiftableRun run,
                                 PriceForecast forecast) {
        String usual = time(result.usualStart());
        String best = time(result.bestStart());
        String usualPrice = centsPerKwh(result.usualCost(), run);
        String bestPrice = centsPerKwh(result.bestCost(), run);

        StringBuilder text = new StringBuilder();
        if (result.outcome() == CheapestStartCalculator.Outcome.AVOID) {
            text.append("A price spike is forecast from ").append(time(result.spikeStart()))
                    .append(": your usual ").append(usual).append(" run would average ").append(usualPrice)
                    .append("/kWh. Starting at ").append(best).append(" instead averages ").append(bestPrice)
                    .append("/kWh and ");
        } else if (result.bestStart().isAfter(result.usualStart())) {
            text.append("Prices are forecast to fall from ").append(usualPrice).append(" to ").append(bestPrice)
                    .append("/kWh by ").append(best).append(". Starting at ").append(best).append(' ');
        } else {
            text.append("Prices are forecast to be lower earlier: ").append(bestPrice).append("/kWh at ")
                    .append(best).append(" against ").append(usualPrice).append("/kWh at your usual ")
                    .append(usual).append(". Starting at ").append(best).append(' ');
        }
        text.append(finishes(result.bestStart(), run)).append(' ');
        text.append("Estimated saving: $").append(money(result.saving())).append(" on this run ($")
                .append(money(result.bestCost())).append(" vs $").append(money(result.usualCost())).append(").");

        if (forecast.isDemo()) {
            text.append(" Based on a demo forecast (a replay of past prices), not a live prediction.");
        } else {
            text.append(" Based on F3's forecast (").append(forecast.model()).append(").");
        }
        return text.toString();
    }

    /** "still finishes by 2:00 PM, before your 9:00 PM limit." (or "at 9:00 PM, your limit.") */
    static String finishes(Instant start, CheapestStartCalculator.ShiftableRun run) {
        LocalTime finish = start.plus(Duration.ofMinutes(run.runMinutes())).atZone(SINGAPORE).toLocalTime();
        String limit = clock(run.mustFinishBy());
        if (finish.equals(run.mustFinishBy())) {
            return "still finishes at " + limit + ", your limit.";
        }
        return "still finishes by " + clock(finish) + ", before your " + limit + " limit.";
    }

    /**
     * Average forecast price over a run, e.g. "31¢" or "9.5¢": cost (SGD) / energy (kWh) x 100.
     * Whole cents from 10¢, one decimal below that, so small prices don't all read as the same.
     */
    static String centsPerKwh(BigDecimal costSgd, CheapestStartCalculator.ShiftableRun run) {
        BigDecimal kwh = run.powerKw().multiply(BigDecimal.valueOf(run.runMinutes()))
                .divide(BigDecimal.valueOf(60), 10, RoundingMode.HALF_UP);
        if (kwh.signum() == 0) {
            return "0¢";
        }
        BigDecimal cents = costSgd.multiply(BigDecimal.valueOf(100)).divide(kwh, 10, RoundingMode.HALF_UP);
        int scale = cents.abs().compareTo(TEN) >= 0 ? 0 : 1;
        return cents.setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "¢";
    }

    static String time(Instant instant) {
        return clock(instant.atZone(SINGAPORE).toLocalTime());
    }

    /** "7:45 PM"; midnight is "midnight" rather than the confusing "12:00 AM". */
    private static String clock(LocalTime time) {
        return time.equals(LocalTime.MIDNIGHT) ? "midnight" : time.format(CLOCK);
    }

    private static String money(BigDecimal sgd) {
        return sgd.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}

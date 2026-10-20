package sg.edu.smu.cs203.recommendation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sg.edu.smu.cs203.household.spend.SpendCalculator;
import sg.edu.smu.cs203.household.spend.TariffComponent;

/**
 * CSDT4-53: what an accepted suggestion really saved, from actual prices.
 *
 *     realised saving = cost of the run at the usual time - cost of the run at the chosen time
 *
 * both at actual prices, worked out the same way as month-to-date spend (CSDT4-41): for each
 * half-hour the run touches, kWh x (USEP / 1000 + every per-kWh charge that applies then), then
 * + GST. kWh in a half-hour = power x the hours the run spends in it, so a run starting at 19:45
 * is split 15 min / 30 min / ... Monthly charges are left out: they are the same whenever you run.
 * Fixed-rate plans pay the same rate at every time, so their saving is 0.
 *
 * A saving is only realised once both runs have finished and every half-hour they touch has an
 * actual price; until then it is pending and never guessed. It can be negative when the actual
 * prices made the chosen time dearer. Money is rounded to cents per cost, and the saving is the
 * difference of the rounded costs, so the list always adds up to the totals.
 *
 * No Spring and no system clock: the caller passes now, so tests are exact.
 */
public final class RealisedSavingsCalculator {

    private static final long HALF_HOUR_SECONDS = 1800;
    private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3600);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    static final String NOT_RUN_YET = "Counted once both the usual and the chosen run have finished.";
    static final String NO_PRICE_YET = "Waiting for the actual market price of a half-hour these runs cover.";

    private RealisedSavingsCalculator() {
    }

    public enum State { REALISED, PENDING }

    /** Costs and saving in SGD (cents); all null while pending, with the reason. */
    public record Outcome(State state, BigDecimal usualCostSgd, BigDecimal chosenCostSgd, BigDecimal savingSgd,
                          String pendingReason) {

        static Outcome pending(String reason) {
            return new Outcome(State.PENDING, null, null, null, reason);
        }

        static Outcome realised(BigDecimal usual, BigDecimal chosen) {
            BigDecimal usualSgd = SpendCalculator.sgd(usual);
            BigDecimal chosenSgd = SpendCalculator.sgd(chosen);
            return new Outcome(State.REALISED, usualSgd, chosenSgd, usualSgd.subtract(chosenSgd), null);
        }
    }

    /** Price-linked plan: actual USEP (period start -> SGD/MWh) plus per-kWh charges and GST. */
    public static Outcome priceLinked(AcceptedRun run, Map<Instant, BigDecimal> actualUsep,
                                      List<TariffComponent> components, Instant now) {
        if (!finished(run, now)) {
            return Outcome.pending(NOT_RUN_YET);
        }
        List<SpendCalculator.HalfHour> usual = halfHours(run.usualStart(), run, actualUsep);
        List<SpendCalculator.HalfHour> chosen = halfHours(run.chosenStart(), run, actualUsep);
        if (usual.stream().anyMatch(h -> h.usepSgdPerMwh() == null)
                || chosen.stream().anyMatch(h -> h.usepSgdPerMwh() == null)) {
            return Outcome.pending(NO_PRICE_YET);
        }
        List<TariffComponent> byTime = components.stream()
                .filter(c -> c.unit() != TariffComponent.Unit.SGD_PER_MONTH)
                .toList();
        return Outcome.realised(SpendCalculator.priceLinked(usual, byTime, 0).totalSgd(),
                SpendCalculator.priceLinked(chosen, byTime, 0).totalSgd());
    }

    /** Fixed-rate plan: the same cents/kWh at every time, so moving the run saves nothing. */
    public static Outcome fixedRate(AcceptedRun run, BigDecimal centsPerKwh, Instant now) {
        if (!finished(run, now)) {
            return Outcome.pending(NOT_RUN_YET);
        }
        BigDecimal rate = centsPerKwh == null ? BigDecimal.ZERO : centsPerKwh;
        BigDecimal cost = kwh(run).multiply(rate).divide(HUNDRED, 10, RoundingMode.HALF_UP);
        return Outcome.realised(cost, cost);
    }

    static boolean finished(AcceptedRun run, Instant now) {
        Duration length = Duration.ofMinutes(run.runMinutes());
        return !run.usualStart().plus(length).isAfter(now) && !run.chosenStart().plus(length).isAfter(now);
    }

    /**
     * The half-hours a run from start touches: each with the kWh used in it and its actual USEP
     * (null if not known). start of each is the half-hour's start, so time-of-day charges apply.
     */
    static List<SpendCalculator.HalfHour> halfHours(Instant start, AcceptedRun run,
                                                    Map<Instant, BigDecimal> actualUsep) {
        Map<Instant, BigDecimal> kwhByPeriod = new LinkedHashMap<>();
        Instant end = start.plus(Duration.ofMinutes(run.runMinutes()));
        Instant cursor = start;
        while (cursor.isBefore(end)) {
            Instant period = Instant.ofEpochSecond(
                    Math.floorDiv(cursor.getEpochSecond(), HALF_HOUR_SECONDS) * HALF_HOUR_SECONDS);
            Instant periodEnd = period.plusSeconds(HALF_HOUR_SECONDS);
            Instant sliceEnd = end.isBefore(periodEnd) ? end : periodEnd;
            BigDecimal hours = BigDecimal.valueOf(Duration.between(cursor, sliceEnd).getSeconds())
                    .divide(SECONDS_PER_HOUR, 10, RoundingMode.HALF_UP);
            kwhByPeriod.merge(period, run.powerKw().multiply(hours), BigDecimal::add);
            cursor = sliceEnd;
        }
        List<SpendCalculator.HalfHour> halfHours = new ArrayList<>();
        kwhByPeriod.forEach((period, kwh) ->
                halfHours.add(new SpendCalculator.HalfHour(period, kwh, actualUsep.get(period))));
        return halfHours;
    }

    private static BigDecimal kwh(AcceptedRun run) {
        return run.powerKw().multiply(BigDecimal.valueOf(run.runMinutes()))
                .divide(BigDecimal.valueOf(60), 10, RoundingMode.HALF_UP);
    }
}

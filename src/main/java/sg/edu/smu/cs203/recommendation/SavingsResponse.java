package sg.edu.smu.cs203.recommendation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Realised savings for this week or this month (CSDT4-53), and the accepted suggestions behind
 * them. totalSavedSgd adds up the REALISED items only; PENDING ones (not run yet, or actual prices
 * not in yet) are listed but not counted. Money is SGD; a negative saving means the chosen time
 * turned out dearer at actual prices.
 *
 * @param from  first day of the period (Singapore date)
 * @param to    last day of the period (inclusive)
 * @param asOf  the clock time the figures were worked out at (the demo clock in demo mode)
 */
public record SavingsResponse(long householdId, SavingsPeriod period, LocalDate from, LocalDate to, Instant asOf,
                              BigDecimal totalSavedSgd, int realisedCount, int pendingCount, List<Item> items,
                              List<String> notes) {

    /**
     * One accepted suggestion. usualCostSgd, chosenCostSgd and savedSgd are null while pending.
     * estimatedSavingSgd is what the forecast said when it was suggested (market price only).
     */
    public record Item(long recommendationId, long applianceId, String applianceName, Instant usualStart,
                       Instant chosenStart, BigDecimal powerKw, int runMinutes, RealisedSavingsCalculator.State state,
                       BigDecimal usualCostSgd, BigDecimal chosenCostSgd, BigDecimal savedSgd,
                       BigDecimal estimatedSavingSgd, String pendingReason) {
    }
}

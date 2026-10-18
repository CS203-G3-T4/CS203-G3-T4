package sg.edu.smu.cs203.household.budget;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import sg.edu.smu.cs203.household.PlanType;

/**
 * CSDT4-42: the one budget projection the Dashboard and the Spending page both show. SGD rounded
 * to cents.
 *
 *   projectedMonthSgd = spentSoFarSgd + averageDailySpendSgd x daysLeft
 *
 * spentSoFarSgd is CSDT4-41's month-to-date spend; averageDailySpendSgd is the mean over the last
 * 7 full days (daysInAverage of them had a price for every half-hour). The projection fields are
 * null when no recent day could be priced (message says why). alert is true when the projection is
 * over the budget and alerts are on.
 */
public record BudgetStatusResponse(
        long householdId,
        YearMonth month,
        Instant asOf,
        PlanType planType,
        BigDecimal monthlyBudgetSgd,
        boolean alertsEnabled,
        BigDecimal spentSoFarSgd,
        BigDecimal averageDailySpendSgd,
        int daysInAverage,
        BigDecimal daysLeft,
        BigDecimal projectedMonthSgd,
        BigDecimal remainingSgd,
        boolean overBudget,
        boolean alert,
        String message,
        List<Day> days) {

    /**
     * One bar of the month: actual spend for days that have passed, the average for days still to
     * come, and for today the spend so far plus the average for the rest of the day.
     */
    public record Day(LocalDate date, BigDecimal spendSgd, String kind) {
        public static final String ACTUAL = "ACTUAL";
        public static final String TODAY = "TODAY";
        public static final String PROJECTED = "PROJECTED";
    }
}

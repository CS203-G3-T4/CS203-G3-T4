package sg.edu.smu.cs203.household.budget;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import sg.edu.smu.cs203.household.HouseholdResponse;
import sg.edu.smu.cs203.household.HouseholdService;
import sg.edu.smu.cs203.household.spend.MonthToDateSpendResponse;
import sg.edu.smu.cs203.household.spend.SpendCalculator;
import sg.edu.smu.cs203.household.spend.SpendService;

/**
 * CSDT4-42: one shared projection of this month's bill, used by both the Dashboard and the
 * Spending page so they always show the same figures:
 *
 *   projected = spend so far (CSDT4-41) + average daily spend over the last 7 full days x days left
 *
 * "Last 7 full days" are the 7 days before today (Singapore time), so early in a month they
 * include last month's days. A day only counts if every half-hour had a market price, so a gap in
 * the price feed doesn't drag the average down. Days left = the time from now to the end of the
 * month, in days. Everything follows the injected Clock.
 */
@Service
public class BudgetStatusService {

    static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
    static final int AVERAGE_DAYS = 7;
    private static final MathContext PRECISE = MathContext.DECIMAL64;
    private static final BigDecimal SECONDS_PER_DAY = BigDecimal.valueOf(86_400);

    private final HouseholdService households;
    private final SpendService spend;
    private final BudgetRepository budgets;
    private final Clock clock;

    public BudgetStatusService(HouseholdService households, SpendService spend, BudgetRepository budgets,
                               Clock clock) {
        this.households = households;
        this.spend = spend;
        this.budgets = budgets;
        this.clock = clock;
    }

    public BudgetStatusResponse status(long householdId) {
        HouseholdResponse household = households.get(householdId);
        BudgetRepository.Settings settings = budgets.find(householdId)
                .orElse(new BudgetRepository.Settings(null, null, true));
        Instant now = clock.instant();
        LocalDate today = now.atZone(SINGAPORE).toLocalDate();
        YearMonth month = YearMonth.from(today);
        LocalDate firstDay = month.atDay(1);
        LocalDate averageFrom = today.minusDays(AVERAGE_DAYS);
        LocalDate from = averageFrom.isBefore(firstDay) ? averageFrom : firstDay;

        MonthToDateSpendResponse soFar = spend.monthToDate(householdId);
        Map<LocalDate, SpendCalculator.Result> daily = spend.dailySpend(householdId, from);

        // Average over the last 7 full days that were fully priced.
        BigDecimal total = BigDecimal.ZERO;
        int counted = 0;
        for (LocalDate day = averageFrom; day.isBefore(today); day = day.plusDays(1)) {
            SpendCalculator.Result result = daily.get(day);
            if (result != null && result.halfHoursSoFar() > 0 && result.halfHoursWithoutPrice() == 0) {
                total = total.add(result.totalSgd());
                counted++;
            }
        }
        BigDecimal average = counted == 0 ? null : total.divide(BigDecimal.valueOf(counted), PRECISE);

        Instant monthEnd = month.plusMonths(1).atDay(1).atStartOfDay(SINGAPORE).toInstant();
        BigDecimal daysLeft = BigDecimal.valueOf(Duration.between(now, monthEnd).getSeconds())
                .divide(SECONDS_PER_DAY, PRECISE);
        BigDecimal spent = soFar.totalSgd();
        BigDecimal projected = average == null ? null : spent.add(average.multiply(daysLeft));

        BigDecimal budget = settings.monthlyBudgetSgd();
        BigDecimal projectedRounded = projected == null ? null : sgd(projected);
        boolean overBudget = budget != null && projectedRounded != null
                && projectedRounded.compareTo(budget) > 0;
        boolean alert = overBudget && settings.alertsEnabled();
        String message = average == null
                ? "Not enough price history in the last 7 days to project this month yet." : null;

        return new BudgetStatusResponse(householdId, month, now, household.planType(), budget,
                settings.alertsEnabled(), spent, average == null ? null : sgd(average), counted,
                daysLeft.setScale(1, RoundingMode.HALF_UP), projectedRounded,
                budget == null || projectedRounded == null ? null : budget.subtract(projectedRounded),
                overBudget, alert, message, days(month, today, now, daily, average));
    }

    /** One bar per day of the month; future days (and the rest of today) use the 7-day average. */
    private static List<BudgetStatusResponse.Day> days(YearMonth month, LocalDate today, Instant now,
                                                       Map<LocalDate, SpendCalculator.Result> daily,
                                                       BigDecimal average) {
        List<BudgetStatusResponse.Day> days = new ArrayList<>();
        for (LocalDate day = month.atDay(1); !day.isAfter(month.atEndOfMonth()); day = day.plusDays(1)) {
            SpendCalculator.Result result = daily.get(day);
            if (day.isBefore(today)) {
                days.add(new BudgetStatusResponse.Day(day, sgd(result.totalSgd()),
                        BudgetStatusResponse.Day.ACTUAL));
            } else if (day.equals(today)) {
                Instant endOfToday = day.plusDays(1).atStartOfDay(SINGAPORE).toInstant();
                BigDecimal restOfToday = BigDecimal.valueOf(Duration.between(now, endOfToday).getSeconds())
                        .divide(SECONDS_PER_DAY, PRECISE);
                BigDecimal value = result.totalSgd()
                        .add(average == null ? BigDecimal.ZERO : average.multiply(restOfToday));
                days.add(new BudgetStatusResponse.Day(day, sgd(value), BudgetStatusResponse.Day.TODAY));
            } else {
                days.add(new BudgetStatusResponse.Day(day, average == null ? null : sgd(average),
                        BudgetStatusResponse.Day.PROJECTED));
            }
        }
        return days;
    }

    private static BigDecimal sgd(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}

package sg.edu.smu.cs203.household.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import sg.edu.smu.cs203.household.DwellingType;
import sg.edu.smu.cs203.household.HouseholdResponse;
import sg.edu.smu.cs203.household.HouseholdService;
import sg.edu.smu.cs203.household.PlanType;
import sg.edu.smu.cs203.household.spend.MonthToDateSpendResponse;
import sg.edu.smu.cs203.household.spend.SpendCalculator;
import sg.edu.smu.cs203.household.spend.SpendService;

/**
 * CSDT4-42: the shared projection against a hand-worked example. Fixed Clock: 10 Oct 2026, noon.
 *
 *   spent so far (1-9 Oct + this morning)          S$50.00
 *   last 7 full days, 3-9 Oct: 4+4+5+5+6+6+5 = 35  average S$5.00/day
 *   days left: noon 10 Oct -> midnight 1 Nov        21.5 days
 *   projection: 50 + 5.00 x 21.5                     S$157.50
 */
class BudgetStatusServiceTest {

    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
    private final Instant noon = sg("2026-10-10T12:00");

    private final HouseholdService households = mock(HouseholdService.class);
    private final SpendService spend = mock(SpendService.class);
    private final BudgetRepository budgets = mock(BudgetRepository.class);

    @Test
    void theProjectionIsSpendSoFarPlusTheSevenDayAverageTimesDaysLeft() {
        example("150", true);

        BudgetStatusResponse status = service(noon).status(1L);

        assertThat(status.month()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(status.spentSoFarSgd()).isEqualByComparingTo("50.00");
        assertThat(status.averageDailySpendSgd()).isEqualByComparingTo("5.00");
        assertThat(status.daysInAverage()).isEqualTo(7);
        assertThat(status.daysLeft()).isEqualByComparingTo("21.5");
        assertThat(status.projectedMonthSgd()).isEqualByComparingTo("157.50");
    }

    @Test
    void overTheBudgetWithAlertsOnShowsTheAlert() {
        example("150", true);

        BudgetStatusResponse status = service(noon).status(1L);

        assertThat(status.overBudget()).isTrue();
        assertThat(status.alert()).isTrue();
        assertThat(status.remainingSgd()).isEqualByComparingTo("-7.50");
    }

    @Test
    void withAlertsOffItIsStillOverBudgetButNoAlert() {
        example("150", false);

        BudgetStatusResponse status = service(noon).status(1L);

        assertThat(status.overBudget()).isTrue();
        assertThat(status.alert()).isFalse();
        assertThat(status.alertsEnabled()).isFalse();
    }

    @Test
    void underTheBudgetThereIsNoAlert() {
        example("200", true);

        BudgetStatusResponse status = service(noon).status(1L);

        assertThat(status.overBudget()).isFalse();
        assertThat(status.alert()).isFalse();
        assertThat(status.remainingSgd()).isEqualByComparingTo("42.50");
    }

    @Test
    void withoutABudgetThereIsAProjectionButNothingToCompare() {
        example(null, true);

        BudgetStatusResponse status = service(noon).status(1L);

        assertThat(status.projectedMonthSgd()).isEqualByComparingTo("157.50");
        assertThat(status.remainingSgd()).isNull();
        assertThat(status.alert()).isFalse();
    }

    @Test
    void theDailyBarsAddUpToTheProjection() {
        example("150", true);

        BudgetStatusResponse status = service(noon).status(1L);

        assertThat(status.days()).hasSize(31);
        assertThat(status.days().get(8).kind()).isEqualTo(BudgetStatusResponse.Day.ACTUAL);   // 9 Oct
        // Today: 2.00 so far + 5.00 x half a day left.
        assertThat(status.days().get(9).kind()).isEqualTo(BudgetStatusResponse.Day.TODAY);
        assertThat(status.days().get(9).spendSgd()).isEqualByComparingTo("4.50");
        assertThat(status.days().get(10).kind()).isEqualTo(BudgetStatusResponse.Day.PROJECTED);
        assertThat(status.days().get(10).spendSgd()).isEqualByComparingTo("5.00");
        BigDecimal sum = status.days().stream().map(BudgetStatusResponse.Day::spendSgd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(status.projectedMonthSgd());
    }

    @Test
    void aDayWithAMissingPriceIsLeftOutOfTheAverage() {
        example("150", true);
        Map<LocalDate, SpendCalculator.Result> daily = month();
        daily.put(LocalDate.of(2026, 10, 9), day("20.00", 3)); // feed gap: would distort the average
        when(spend.dailySpend(1L, LocalDate.of(2026, 10, 1))).thenReturn(daily);

        BudgetStatusResponse status = service(noon).status(1L);

        // 3-8 Oct: 4+4+5+5+6+6 = 30 over 6 days.
        assertThat(status.daysInAverage()).isEqualTo(6);
        assertThat(status.averageDailySpendSgd()).isEqualByComparingTo("5.00");
    }

    @Test
    void inTheFirstWeekTheAverageUsesLastMonthsDays() {
        Instant secondOct = sg("2026-10-02T10:00");
        when(households.get(1L)).thenReturn(household());
        when(budgets.find(1L)).thenReturn(Optional.empty());
        when(spend.monthToDate(1L)).thenReturn(spent("3.00"));
        Map<LocalDate, SpendCalculator.Result> daily = new LinkedHashMap<>();
        for (LocalDate d = LocalDate.of(2026, 9, 25); !d.isAfter(LocalDate.of(2026, 10, 2)); d = d.plusDays(1)) {
            daily.put(d, day("4.00", 0));
        }
        when(spend.dailySpend(1L, LocalDate.of(2026, 9, 25))).thenReturn(daily);

        BudgetStatusResponse status = service(secondOct).status(1L);

        verify(spend).dailySpend(1L, LocalDate.of(2026, 9, 25));
        assertThat(status.daysInAverage()).isEqualTo(7); // 25 Sep - 1 Oct
        assertThat(status.averageDailySpendSgd()).isEqualByComparingTo("4.00");
        assertThat(status.alertsEnabled()).isTrue();     // on by default
    }

    @Test
    void withoutRecentPricesThereIsNoProjectionAndNoAlert() {
        when(households.get(1L)).thenReturn(household());
        when(budgets.find(1L)).thenReturn(Optional.of(new BudgetRepository.Settings(new BigDecimal("10"), null, true)));
        when(spend.monthToDate(1L)).thenReturn(spent("0.00"));
        Map<LocalDate, SpendCalculator.Result> daily = new LinkedHashMap<>();
        for (LocalDate d = LocalDate.of(2026, 10, 1); !d.isAfter(LocalDate.of(2026, 10, 10)); d = d.plusDays(1)) {
            daily.put(d, day("0.00", 48));
        }
        when(spend.dailySpend(1L, LocalDate.of(2026, 10, 1))).thenReturn(daily);

        BudgetStatusResponse status = service(noon).status(1L);

        assertThat(status.projectedMonthSgd()).isNull();
        assertThat(status.alert()).isFalse();
        assertThat(status.message()).startsWith("Not enough price history");
    }

    // ------------------------------------------------------------ the example

    private void example(String budget, boolean alerts) {
        when(households.get(1L)).thenReturn(household());
        when(budgets.find(1L)).thenReturn(Optional.of(new BudgetRepository.Settings(
                budget == null ? null : new BigDecimal(budget), null, alerts)));
        when(spend.monthToDate(1L)).thenReturn(spent("50.00"));
        when(spend.dailySpend(1L, LocalDate.of(2026, 10, 1))).thenReturn(month());
    }

    /** 1-2 Oct S$6.50 each, 3-9 Oct 4,4,5,5,6,6,5, and S$2.00 so far today: S$50.00 in all. */
    private static Map<LocalDate, SpendCalculator.Result> month() {
        Map<LocalDate, SpendCalculator.Result> days = new LinkedHashMap<>();
        String[] totals = {"6.50", "6.50", "4", "4", "5", "5", "6", "6", "5"};
        for (int i = 0; i < totals.length; i++) {
            days.put(LocalDate.of(2026, 10, i + 1), day(totals[i], 0));
        }
        days.put(LocalDate.of(2026, 10, 10), new SpendCalculator.Result(new BigDecimal("2.00"), List.of(),
                new BigDecimal("2.00"), BigDecimal.ZERO, new BigDecimal("2.00"), BigDecimal.ONE, BigDecimal.ZERO,
                24, 24, 0));
        return days;
    }

    private static SpendCalculator.Result day(String total, int halfHoursWithoutPrice) {
        BigDecimal value = new BigDecimal(total);
        return new SpendCalculator.Result(value, List.of(), value, BigDecimal.ZERO, value, BigDecimal.TEN,
                BigDecimal.ZERO, 48, 48 - halfHoursWithoutPrice, halfHoursWithoutPrice);
    }

    private static MonthToDateSpendResponse spent(String total) {
        return new MonthToDateSpendResponse(1L, YearMonth.of(2026, 10), Instant.EPOCH, PlanType.PRICE_LINKED, null,
                new BigDecimal(total), new BigDecimal(total), BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.ZERO, 0, 0, 0,
                List.of(), List.of());
    }

    private BudgetStatusService service(Instant now) {
        return new BudgetStatusService(households, spend, budgets, Clock.fixed(now, SINGAPORE));
    }

    private static HouseholdResponse household() {
        return new HouseholdResponse(1L, "The Tan Household", DwellingType.HDB_4_ROOM, 4, PlanType.PRICE_LINKED,
                null, null, true, "Price-linked (USEP)", false, null);
    }

    private static Instant sg(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(SINGAPORE).toInstant();
    }
}

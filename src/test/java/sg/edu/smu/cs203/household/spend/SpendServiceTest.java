package sg.edu.smu.cs203.household.spend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import sg.edu.smu.cs203.household.DwellingType;
import sg.edu.smu.cs203.household.HouseholdResponse;
import sg.edu.smu.cs203.household.HouseholdService;
import sg.edu.smu.cs203.household.PlanType;
import sg.edu.smu.cs203.household.load.LoadProfileResponse;
import sg.edu.smu.cs203.household.load.LoadProfileService;
import sg.edu.smu.cs203.household.budget.BudgetRepository;
import sg.edu.smu.cs203.market.price.MarketHistoryRepository;
import sg.edu.smu.cs203.market.price.MarketRevision;

/** CSDT4-41: "this month" and "so far" come from the injected Clock. Fixed Clock throughout. */
class SpendServiceTest {

    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");

    private final HouseholdService households = mock(HouseholdService.class);
    private final LoadProfileService loads = mock(LoadProfileService.class);
    private final MarketHistoryRepository prices = mock(MarketHistoryRepository.class);
    private final TariffRepository tariffs = mock(TariffRepository.class);
    private final BudgetRepository budgets = mock(BudgetRepository.class);

    @Test
    void onlyFinishedHalfHoursOfThisMonthAreCountedWithTheirOwnPrices() {
        // 01:10 on 1 Oct: 00:00 and 00:30 have finished; 01:00 hasn't. Only 00:00 has a price.
        Instant now = sg("2026-10-01T01:10");
        priceLinked();
        when(loads.profile(1L, LocalDate.of(2026, 10, 1))).thenReturn(day(LocalDate.of(2026, 10, 1), "0.5"));
        when(prices.asOf(now, sg("2026-10-01T00:00"))).thenReturn(List.of(price("2026-10-01T00:00", "200")));
        when(tariffs.forPeriod(PlanType.PRICE_LINKED, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1)))
                .thenReturn(List.of(component("TRANSMISSION_OFF_PEAK", "0.0478", "23:00", "07:00"),
                        new TariffComponent("GST", "GST (9%)", TariffComponent.Unit.PERCENT, new BigDecimal("9"),
                                null, null, LocalDate.of(2026, 7, 1), null, "SP Group", null)));

        MonthToDateSpendResponse spend = service(now).monthToDate(1L);

        // 0.5 x (0.200 + 0.0478) x 1.09 = 0.135051 -> $0.14
        assertThat(spend.month()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(spend.halfHoursSoFar()).isEqualTo(2);
        assertThat(spend.halfHoursCounted()).isEqualTo(1);
        assertThat(spend.halfHoursWithoutPrice()).isEqualTo(1);
        assertThat(spend.totalSgd()).isEqualByComparingTo("0.14");
        assertThat(spend.energySgd()).isEqualByComparingTo("0.10");
        assertThat(spend.charges()).extracting(MonthToDateSpendResponse.Charge::code)
                .containsExactly("TRANSMISSION_OFF_PEAK", "GST");
        assertThat(spend.notes()).anyMatch(note -> note.startsWith("1 half-hours have no saved market price"));
    }

    @Test
    void theMonthFollowsTheClockSoTheDemoClockGivesTheDemoMonth() {
        // A demo clock on 26 Sep 2026 at noon: September, 25 full days plus 24 half-hours.
        Instant demo = sg("2026-09-26T12:00");
        priceLinked();
        when(loads.profile(eq(1L), any())).thenAnswer(call -> day(call.getArgument(1), "0.1"));

        MonthToDateSpendResponse spend = service(demo).monthToDate(1L);

        assertThat(spend.month()).isEqualTo(YearMonth.of(2026, 9));
        assertThat(spend.asOf()).isEqualTo(demo);
        assertThat(spend.halfHoursSoFar()).isEqualTo(25 * 48 + 24);
        verify(prices).asOf(demo, sg("2026-09-01T00:00"));
        verify(loads, times(26)).profile(eq(1L), any());
    }

    @Test
    void aFixedRatePlanUsesTheContractedRateAndNeedsNoMarketPrices() {
        Instant now = sg("2026-10-01T01:00");
        when(households.get(1L)).thenReturn(household(PlanType.FIXED, new BigDecimal("29.5")));
        when(loads.profile(1L, LocalDate.of(2026, 10, 1))).thenReturn(day(LocalDate.of(2026, 10, 1), "1.0"));

        MonthToDateSpendResponse spend = service(now).monthToDate(1L);

        // 2 half-hours x 1.0 kWh x 29.5 cents = $0.59
        assertThat(spend.totalSgd()).isEqualByComparingTo("0.59");
        assertThat(spend.charges()).isEmpty();
        verify(prices, never()).asOf(any(), any());
    }

    @Test
    void justAfterMidnightOnTheFirstNothingHasBeenSpentYet() {
        Instant now = sg("2026-11-01T00:10");
        priceLinked();
        when(loads.profile(1L, LocalDate.of(2026, 11, 1))).thenReturn(day(LocalDate.of(2026, 11, 1), "1.0"));

        MonthToDateSpendResponse spend = service(now).monthToDate(1L);

        assertThat(spend.month()).isEqualTo(YearMonth.of(2026, 11));
        assertThat(spend.halfHoursSoFar()).isZero();
        assertThat(spend.totalSgd()).isEqualByComparingTo("0.00");
    }

    @Test
    void dailySpendWorksOutEachDayTheSameWayIncludingLastMonthsDays() {
        // 2 Oct at 01:00: 30 Sep and 1 Oct are full days (48 half-hours), 2 Oct has 2 so far.
        Instant now = sg("2026-10-02T01:00");
        when(households.get(1L)).thenReturn(household(PlanType.FIXED, new BigDecimal("30")));
        when(loads.profile(eq(1L), any())).thenAnswer(call -> day(call.getArgument(1), "0.5"));

        var days = service(now).dailySpend(1L, LocalDate.of(2026, 9, 30));

        assertThat(days.keySet()).containsExactly(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 2));
        // 48 x 0.5 kWh x 30 cents = S$7.20 for a full day; 2 x 0.5 x 0.30 = S$0.30 so far today.
        assertThat(days.get(LocalDate.of(2026, 9, 30)).totalSgd()).isEqualByComparingTo("7.20");
        assertThat(days.get(LocalDate.of(2026, 10, 2)).totalSgd()).isEqualByComparingTo("0.30");
        assertThat(days.get(LocalDate.of(2026, 10, 2)).halfHoursSoFar()).isEqualTo(2);
    }

    @Test
    void weeklySplitsTheMonthCalculatesBudgetPaceAndMarksCurrentWeek() {
        Instant now = sg("2026-10-15T12:00");
        when(households.get(1L)).thenReturn(household(PlanType.FIXED, new BigDecimal("30")));
        when(loads.profile(eq(1L), any())).thenAnswer(call -> day(call.getArgument(1), "1.0"));
        when(budgets.find(1L)).thenReturn(java.util.Optional.of(
                new BudgetRepository.Settings(new BigDecimal("310.00"), null, true)));

        WeeklySpendResponse result = service(now).weekly(1L, YearMonth.of(2026, 10));

        assertThat(result.month()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(result.monthlyBudgetSgd()).isEqualByComparingTo("310.00");
        assertThat(result.weeks()).extracting(WeeklySpendResponse.Week::from)
                .containsExactly(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 5),
                        LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 19),
                        LocalDate.of(2026, 10, 26));
        assertThat(result.weeks()).extracting(WeeklySpendResponse.Week::spentSgd)
                .containsExactly(new BigDecimal("57.60"), new BigDecimal("100.80"),
                        new BigDecimal("50.40"), new BigDecimal("0.00"), new BigDecimal("0.00"));
        assertThat(result.weeks()).extracting(WeeklySpendResponse.Week::budgetPaceSgd)
                .containsExactly(new BigDecimal("40.00"), new BigDecimal("70.00"),
                        new BigDecimal("70.00"), new BigDecimal("70.00"), new BigDecimal("60.00"));
        assertThat(result.weeks()).extracting(WeeklySpendResponse.Week::current)
                .containsExactly(false, false, true, false, false);
    }

    private SpendService service(Instant now) {
        return new SpendService(households, loads, prices, tariffs, budgets, Clock.fixed(now, SINGAPORE));
    }

    private void priceLinked() {
        when(households.get(1L)).thenReturn(household(PlanType.PRICE_LINKED, null));
    }

    private static HouseholdResponse household(PlanType plan, BigDecimal fixedRate) {
        boolean exposed = plan == PlanType.PRICE_LINKED;
        return new HouseholdResponse(1L, "The Tan Household", DwellingType.HDB_4_ROOM, 4, plan, fixedRate, null,
                exposed, exposed ? "Price-linked (USEP)" : "Not exposed (fixed rate)", false, null);
    }

    /** A day where every half-hour uses the same kWh. */
    private static LoadProfileResponse day(LocalDate date, String kwhPerHalfHour) {
        List<LoadProfileResponse.Slot> slots = new ArrayList<>();
        BigDecimal kwh = new BigDecimal(kwhPerHalfHour);
        for (int i = 0; i < 48; i++) {
            slots.add(new LoadProfileResponse.Slot(LocalTime.MIDNIGHT.plusMinutes(30L * i), kwh, BigDecimal.ZERO,
                    BigDecimal.ZERO, kwh));
        }
        return new LoadProfileResponse(1L, date, "HDB 4-room", new BigDecimal("400"), true,
                kwh.multiply(BigDecimal.valueOf(48)), kwh.multiply(BigDecimal.valueOf(48)), BigDecimal.ZERO,
                BigDecimal.ZERO, slots, List.of());
    }

    private static MarketRevision price(String localPeriod, String usep) {
        Instant period = sg(localPeriod);
        return new MarketRevision(1L, "EMC", period, period, period, new BigDecimal(usep), null, null, "FINAL",
                "VERIFIED");
    }

    private static TariffComponent component(String code, String rate, String from, String to) {
        return new TariffComponent(code, code, TariffComponent.Unit.SGD_PER_KWH, new BigDecimal(rate),
                LocalTime.parse(from), LocalTime.parse(to), LocalDate.of(2026, 7, 1), null, "SP Group", null);
    }

    private static Instant sg(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(SINGAPORE).toInstant();
    }
}

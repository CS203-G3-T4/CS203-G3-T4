package sg.edu.smu.cs203.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static sg.edu.smu.cs203.recommendation.RealisedSavingsCalculatorTest.charge;
import static sg.edu.smu.cs203.recommendation.RealisedSavingsCalculatorTest.sg;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import sg.edu.smu.cs203.common.DemoClock;
import sg.edu.smu.cs203.household.DwellingType;
import sg.edu.smu.cs203.household.HouseholdDirectory;
import sg.edu.smu.cs203.household.HouseholdResponse;
import sg.edu.smu.cs203.household.PlanType;
import sg.edu.smu.cs203.household.spend.TariffComponent.Unit;
import sg.edu.smu.cs203.household.spend.TariffRepository;
import sg.edu.smu.cs203.market.price.AsOfMarketPrices;

/** CSDT4-53: this week and this month come from a fixed Clock; actual prices are known and fixed. */
class RealisedSavingsServiceTest {

    /** Thursday 15 Oct 2026, 10:00 Singapore time. */
    private static final Instant NOW = sg("2026-10-15T10:00");

    private final RecommendationRepository recommendations = mock(RecommendationRepository.class);
    private final HouseholdDirectory households = mock(HouseholdDirectory.class);
    private final AsOfMarketPrices prices = mock(AsOfMarketPrices.class);
    private final TariffRepository tariffs = mock(TariffRepository.class);
    private final RealisedSavingsService service = new RealisedSavingsService(recommendations, households, prices,
            tariffs, Clock.fixed(NOW, ZoneId.of("Asia/Singapore")));

    /** Yesterday: usual 19:00, chosen 13:00 (realised, saves $0.41 as in RealisedSavingsCalculatorTest). */
    private final AcceptedRun yesterday = new AcceptedRun(5L, 10L, "Tumble Dryer", sg("2026-10-14T19:00"),
            sg("2026-10-14T13:00"), new BigDecimal("2.0"), 60, new BigDecimal("0.30"));
    /** Tonight: not run yet, so pending. */
    private final AcceptedRun tonight = new AcceptedRun(6L, 11L, "Washing Machine", sg("2026-10-15T20:00"),
            sg("2026-10-15T14:00"), new BigDecimal("0.5"), 90, new BigDecimal("0.08"));

    @Test
    void thisWeekRunsMondayToSundayAndTotalsOnlyRealisedSavings() {
        priceLinked();
        Instant monday = sg("2026-10-12T00:00");
        Instant nextMonday = sg("2026-10-19T00:00");
        when(recommendations.findAcceptedRuns(1L, monday, nextMonday)).thenReturn(List.of(yesterday, tonight));
        when(prices.prices(sg("2026-10-14T13:00"), NOW)).thenReturn(java.util.Map.of(
                sg("2026-10-14T13:00"), new BigDecimal("120"),
                sg("2026-10-14T13:30"), new BigDecimal("100"),
                sg("2026-10-14T19:00"), new BigDecimal("300"),
                sg("2026-10-14T19:30"), new BigDecimal("260")));
        when(tariffs.forPeriod(PlanType.PRICE_LINKED, LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 15)))
                .thenReturn(List.of(charge("NETWORK", Unit.SGD_PER_KWH, "0.0500", null, null),
                        charge("EVENING", Unit.SGD_PER_KWH, "0.0200", "17:00", "23:00"),
                        charge("GST", Unit.PERCENT, "9", null, null)));

        SavingsResponse week = service.savings(1L, SavingsPeriod.WEEK);

        assertThat(week.from()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(week.to()).isEqualTo(LocalDate.of(2026, 10, 18));
        assertThat(week.asOf()).isEqualTo(NOW);
        assertThat(week.totalSavedSgd()).isEqualByComparingTo("0.41");
        assertThat(week.realisedCount()).isEqualTo(1);
        assertThat(week.pendingCount()).isEqualTo(1);
        assertThat(week.items()).extracting(SavingsResponse.Item::recommendationId).containsExactly(5L, 6L);
        SavingsResponse.Item realised = week.items().getFirst();
        assertThat(realised.state()).isEqualTo(RealisedSavingsCalculator.State.REALISED);
        assertThat(realised.usualCostSgd()).isEqualByComparingTo("0.76");
        assertThat(realised.chosenCostSgd()).isEqualByComparingTo("0.35");
        assertThat(realised.savedSgd()).isEqualByComparingTo("0.41");
        assertThat(realised.estimatedSavingSgd()).isEqualByComparingTo("0.30");
        SavingsResponse.Item pending = week.items().get(1);
        assertThat(pending.state()).isEqualTo(RealisedSavingsCalculator.State.PENDING);
        assertThat(pending.savedSgd()).isNull();
        assertThat(week.notes()).contains(RealisedSavingsService.FOLLOWED_NOTE,
                RealisedSavingsService.PRICE_LINKED_NOTE, RealisedSavingsService.PENDING_NOTE);
    }

    @Test
    void thisMonthIsTheCalendarMonthOfTheClock() {
        priceLinked();
        when(recommendations.findAcceptedRuns(anyLong(), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());

        SavingsResponse month = service.savings(1L, SavingsPeriod.MONTH);

        verify(recommendations).findAcceptedRuns(1L, sg("2026-10-01T00:00"), sg("2026-11-01T00:00"));
        assertThat(month.from()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(month.to()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(month.totalSavedSgd()).isEqualByComparingTo("0.00");
        assertThat(month.items()).isEmpty();
        assertThat(month.notes()).containsExactly(RealisedSavingsService.FOLLOWED_NOTE,
                RealisedSavingsService.PRICE_LINKED_NOTE);
        verify(prices, never()).prices(any(), any());
    }

    @Test
    void realisedSavingsUsesReplayPricesWhileTheDemoClockIsRunning() {
        priceLinked();
        Instant monday = sg("2026-10-12T00:00");
        Instant nextMonday = sg("2026-10-19T00:00");
        when(recommendations.findAcceptedRuns(1L, monday, nextMonday)).thenReturn(List.of(yesterday));
        when(prices.prices(sg("2026-10-14T13:00"), NOW)).thenReturn(java.util.Map.of(
                sg("2026-10-14T13:00"), new BigDecimal("120"),
                sg("2026-10-14T13:30"), new BigDecimal("100"),
                sg("2026-10-14T19:00"), new BigDecimal("300"),
                sg("2026-10-14T19:30"), new BigDecimal("260")));
        DemoClock clock = new DemoClock(Clock.fixed(NOW, ZoneId.of("Asia/Singapore")));
        clock.startAt(NOW);
        RealisedSavingsService replayService = new RealisedSavingsService(recommendations, households, prices,
                tariffs, clock);
        replayService.savings(1L, SavingsPeriod.WEEK);

        verify(prices).prices(sg("2026-10-14T13:00"), NOW);
    }

    @Test
    void aFixedRateHouseholdSavesNothingAndNeedsNoMarketPrices() {
        when(households.household(1L)).thenReturn(household(false));
        when(recommendations.findAcceptedRuns(eq(1L), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(yesterday));

        SavingsResponse week = service.savings(1L, SavingsPeriod.WEEK);

        assertThat(week.totalSavedSgd()).isEqualByComparingTo("0.00");
        assertThat(week.items().getFirst().usualCostSgd()).isEqualByComparingTo("0.59");
        assertThat(week.notes()).contains(RealisedSavingsService.FIXED_RATE_NOTE);
        verify(prices, never()).prices(any(), any());
    }

    @Test
    void cardsGetTheRealisedSavingOnlyOnceItIsKnown() {
        priceLinked();
        when(recommendations.findAcceptedRuns(1L, List.of(5L, 6L))).thenReturn(List.of(yesterday, tonight));
        when(prices.prices(any(), eq(NOW))).thenReturn(java.util.Map.of(
                sg("2026-10-14T13:00"), new BigDecimal("120"),
                sg("2026-10-14T13:30"), new BigDecimal("100"),
                sg("2026-10-14T19:00"), new BigDecimal("300"),
                sg("2026-10-14T19:30"), new BigDecimal("260")));
        when(tariffs.forPeriod(any(), any(), any())).thenReturn(List.of());

        Map<Long, BigDecimal> saved = service.realisedSavings(1L, List.of(5L, 6L));

        // Market price only (no charges recorded): usual 0.300 + 0.260 = $0.56, chosen 0.120 + 0.100 = $0.22
        assertThat(saved).containsOnlyKeys(5L);
        assertThat(saved.get(5L)).isEqualByComparingTo("0.34");
    }

    @Test
    void noAcceptedSuggestionsMeansNoLookups() {
        when(recommendations.findAcceptedRuns(anyLong(), anyList())).thenReturn(List.of());

        assertThat(service.realisedSavings(1L, List.of(7L))).isEmpty();
        verify(households, never()).household(anyLong());
    }

    private void priceLinked() {
        when(households.household(1L)).thenReturn(household(true));
    }

    private static HouseholdResponse household(boolean exposed) {
        return new HouseholdResponse(1L, "The Tan Household", DwellingType.HDB_4_ROOM, 4,
                exposed ? PlanType.PRICE_LINKED : PlanType.FIXED, exposed ? null : new BigDecimal("29.5"),
                null, exposed, exposed ? "Price-linked (USEP)" : "Not exposed (fixed rate)", false, null);
    }

}

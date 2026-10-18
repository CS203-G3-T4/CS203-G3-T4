package sg.edu.smu.cs203.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import sg.edu.smu.cs203.household.DwellingType;
import sg.edu.smu.cs203.household.HouseholdDirectory;
import sg.edu.smu.cs203.household.HouseholdResponse;
import sg.edu.smu.cs203.household.PlanType;
import sg.edu.smu.cs203.household.appliance.ApplianceResponse;
import sg.edu.smu.cs203.household.appliance.ApplianceType;
import sg.edu.smu.cs203.household.appliance.Flexibility;

/**
 * CSDT4-37: one test per guard (time window, past start, disabled appliance, fixed-rate, stale
 * feed) and for the ranking (by saving, at most 3 open, nothing under $0.05). Fixed Clock throughout.
 */
class RecommendationGuardsAndRankingTest {

    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
    private final Instant now = sg("2026-09-30T08:10");
    private final Clock clock = Clock.fixed(now, SINGAPORE);

    private final RecommendationRepository repository = mock(RecommendationRepository.class);
    private final HouseholdDirectory households = mock(HouseholdDirectory.class);
    private final PriceForecastSource forecasts = mock(PriceForecastSource.class);
    private final PriceFeedStatus feed = mock(PriceFeedStatus.class);
    private final RecommendationService service = service(false);

    // ---------------------------------------------------------------- guards

    @Test
    void timeWindowGuardNeverSuggestsARunThatFinishesAfterTheDeadline() {
        // The cheapest half-hours are 21:00-22:30, but the dryer must finish by 21:00.
        priceLinked(appliance(10, "Tumble Dryer", "2.5", 60, "07:00", "21:00", "19:45"));
        forecast(PriceForecast.DEMO, sg("2026-09-30T08:30"),
                Map.of("20:00", 50.0, "20:30", 50.0, "21:00", 1.0, "21:30", 1.0, "22:00", 1.0));

        service.generate(1L);

        Recommendation saved = onlyInsert();
        assertThat(saved.suggestedStart()).isEqualTo(sg("2026-09-30T20:00"));
        assertThat(saved.suggestedStart().plus(Duration.ofMinutes(60))).isBeforeOrEqualTo(sg("2026-09-30T21:00"));
    }

    @Test
    void timeWindowGuardHandlesAWindowThatCrossesMidnight() {
        // EV charger 22:00 -> 07:00 for 180 minutes: 07:00 itself is cheapest but outside the window.
        priceLinked(appliance(11, "EV Charger", "7.2", 180, "22:00", "07:00", "22:00"));
        forecast(PriceForecast.DEMO, sg("2026-09-30T08:30"),
                Map.of("04:00", 100.0, "04:30", 100.0, "05:00", 90.0, "05:30", 90.0, "06:00", 90.0,
                        "06:30", 5.0, "07:00", 1.0, "07:30", 1.0));

        service.generate(1L);

        assertThat(onlyInsert().suggestedStart()).isEqualTo(sg("2026-10-01T04:00"));
    }

    @Test
    void pastStartGuardNeverSuggestsAStartBeforeNow() {
        // F3's forecast can include the half-hour that has already begun (08:00 at 08:10).
        priceLinked(appliance(10, "Tumble Dryer", "2.5", 60, "07:00", "21:00", "19:45"));
        forecast(PriceForecast.F3, sg("2026-09-30T08:00"), Map.of("08:00", 1.0, "08:30", 1.0, "09:00", 1.0));

        service.generate(1L);

        assertThat(onlyInsert().suggestedStart()).isAfterOrEqualTo(now);
    }

    @Test
    void disabledApplianceGuardClosesItsOpenSuggestion() {
        // HouseholdDirectory only returns enabled FLEXIBLE appliances, so a switched-off dryer is missing.
        when(households.household(1L)).thenReturn(household(true));
        when(households.flexibleAppliances(1L)).thenReturn(List.of());
        forecast(PriceForecast.DEMO, sg("2026-09-30T08:30"), cheapAt13());
        when(repository.findActive(1L)).thenReturn(List.of(open(5L, 10L, "0.70")));
        when(repository.closeActive(5L, RecommendationStatus.SUPERSEDED, now, null)).thenReturn(true);

        service.generate(1L);

        verify(repository).logEvent(5L, "SUPERSEDED", now, null, false);
        verify(repository, never()).insert(any());
    }

    @Test
    void fixedRateGuardGivesNoSuggestionsAndClosesOpenOnes() {
        when(households.household(1L)).thenReturn(household(false));
        when(repository.findActive(1L)).thenReturn(List.of(open(5L, 10L, "0.70")));
        when(repository.closeActive(5L, RecommendationStatus.SUPERSEDED, now, null)).thenReturn(true);

        RecommendationsResponse response = service.generate(1L);

        assertThat(response.message()).isEqualTo(RecommendationService.FIXED_RATE_MESSAGE);
        verify(repository).logEvent(5L, "SUPERSEDED", now, null, false);
        verify(forecasts, never()).forecast();
        verify(repository, never()).insert(any());
    }

    @Test
    void staleFeedGuardBlocksSuggestionsFromF3sForecast() {
        priceLinked(appliance(10, "Tumble Dryer", "2.5", 60, "07:00", "21:00", "19:45"));
        forecast(PriceForecast.F3, sg("2026-09-30T08:30"), cheapAt13());
        when(feed.stale()).thenReturn(true);

        RecommendationsResponse response = service.generate(1L);

        assertThat(response.message()).isEqualTo(RecommendationService.STALE_FEED_MESSAGE);
        assertThat(response.forecastSource()).isNull();
        verify(repository, never()).insert(any());
        ArgumentCaptor<RecommendationRepository.RefreshStatus> status =
                ArgumentCaptor.forClass(RecommendationRepository.RefreshStatus.class);
        verify(repository).saveRefresh(eq(1L), status.capture());
        assertThat(status.getValue().message()).isEqualTo(RecommendationService.STALE_FEED_MESSAGE);
        assertThat(status.getValue().refreshedAt()).isEqualTo(now);
    }

    @Test
    void aStaleFeedDoesNotBlockTheLabelledDemoForecastByDefault() {
        priceLinked(appliance(10, "Tumble Dryer", "2.5", 60, "07:00", "21:00", "19:45"));
        forecast(PriceForecast.DEMO, sg("2026-09-30T08:30"), cheapAt13());
        when(feed.stale()).thenReturn(true);

        service.generate(1L);

        assertThat(onlyInsert().forecastSource()).isEqualTo(PriceForecast.DEMO);
    }

    @Test
    void aStaleFeedBlocksTheDemoForecastTooWhenConfigured() {
        RecommendationService strict = service(true);
        priceLinked(appliance(10, "Tumble Dryer", "2.5", 60, "07:00", "21:00", "19:45"));
        forecast(PriceForecast.DEMO, sg("2026-09-30T08:30"), cheapAt13());
        when(feed.stale()).thenReturn(true);

        assertThat(strict.generate(1L).message()).isEqualTo(RecommendationService.STALE_FEED_MESSAGE);
        verify(repository, never()).insert(any());
    }

    // ---------------------------------------------------------------- ranking

    @Test
    void onlyTheThreeBiggestSavingsAreOpenedInSavingOrder() {
        // Same forecast for all, so the saving grows with power: 2.5 > 2.0 > 1.0 > 0.5 kW.
        priceLinked(
                appliance(10, "Small", "0.5", 60, "07:00", "21:00", "19:45"),
                appliance(11, "Dryer", "2.5", 60, "07:00", "21:00", "19:45"),
                appliance(12, "Heater", "1.0", 60, "07:00", "21:00", "19:45"),
                appliance(13, "Washer", "2.0", 60, "07:00", "21:00", "19:45"));
        forecast(PriceForecast.DEMO, sg("2026-09-30T08:30"), cheapAt13());
        when(repository.insert(any())).thenReturn(1L, 2L, 3L);

        service.generate(1L);

        ArgumentCaptor<Recommendation> saved = ArgumentCaptor.forClass(Recommendation.class);
        verify(repository, times(3)).insert(saved.capture());
        assertThat(saved.getAllValues()).extracting(Recommendation::applianceName)
                .containsExactly("Dryer", "Washer", "Heater");
        assertThat(saved.getAllValues()).extracting(Recommendation::estSaving)
                .isSortedAccordingTo((a, b) -> b.compareTo(a));
    }

    @Test
    void savingsBelowFiveCentsAreSkipped() {
        // 0.1 kW for an hour moved from ~300 to 20 SGD/MWh saves about $0.028.
        priceLinked(appliance(10, "Tiny", "0.1", 60, "07:00", "21:00", "19:45"));
        forecast(PriceForecast.DEMO, sg("2026-09-30T08:30"), cheapAt13());

        service.generate(1L);

        verify(repository, never()).insert(any());
    }

    @Test
    void anOpenSuggestionPushedOutOfTheTopThreeIsClosed() {
        priceLinked(
                appliance(10, "Small", "0.5", 60, "07:00", "21:00", "19:45"),
                appliance(11, "Dryer", "2.5", 60, "07:00", "21:00", "19:45"),
                appliance(12, "Heater", "1.0", 60, "07:00", "21:00", "19:45"),
                appliance(13, "Washer", "2.0", 60, "07:00", "21:00", "19:45"));
        forecast(PriceForecast.DEMO, sg("2026-09-30T08:30"), cheapAt13());
        when(repository.findActive(1L)).thenReturn(List.of(open(5L, 10L, "0.14")));
        when(repository.closeActive(5L, RecommendationStatus.SUPERSEDED, now, null)).thenReturn(true);
        when(repository.insert(any())).thenReturn(1L, 2L, 3L);

        service.generate(1L);

        verify(repository).logEvent(5L, "SUPERSEDED", now, null, false);
        verify(repository, times(3)).insert(any());
    }

    @Test
    void aTieInSavingGoesToTheEarlierStart() {
        CheapestStartCalculator.Result later = result("2026-09-30T15:00", "0.50");
        CheapestStartCalculator.Result earlier = result("2026-09-30T13:00", "0.50");
        List<RecommendationService.Candidate> ranked = RecommendationService.rank(List.of(
                new RecommendationService.Candidate(appliance(10, "A", "1.0", 60, "07:00", "21:00", "19:45"), later),
                new RecommendationService.Candidate(appliance(11, "B", "1.0", 60, "07:00", "21:00", "19:45"), earlier)),
                3);

        assertThat(ranked).extracting(c -> c.appliance().name()).containsExactly("B", "A");
    }

    @Test
    void theListIsWhatTheLatestBackgroundRecomputeLeftBehind() {
        when(households.household(1L)).thenReturn(household(true));
        when(repository.findRefresh(1L)).thenReturn(java.util.Optional.of(new RecommendationRepository.RefreshStatus(
                sg("2026-09-30T08:01"), PriceForecast.DEMO, "Replay day", null)));

        RecommendationsResponse response = service.list(1L);

        assertThat(response.demoForecast()).isTrue();
        assertThat(response.refreshedAt()).isEqualTo(sg("2026-09-30T08:01"));
        verify(forecasts, never()).forecast();
        verify(repository, never()).insert(any());
    }

    @Test
    void beforeTheFirstRecomputeTheListSaysSuggestionsAreComing() {
        when(households.household(1L)).thenReturn(household(true));

        RecommendationsResponse response = service.list(1L);

        assertThat(response.message()).isEqualTo(RecommendationService.NOT_YET_MESSAGE);
        assertThat(response.refreshedAt()).isNull();
    }

    // ---------------------------------------------------------------- helpers

    private RecommendationService service(boolean staleFeedBlocksDemo) {
        return new RecommendationService(repository, households, forecasts, feed, clock,
                new BigDecimal("0.05"), 3, staleFeedBlocksDemo);
    }

    private void priceLinked(ApplianceResponse... appliances) {
        when(households.household(1L)).thenReturn(household(true));
        when(households.flexibleAppliances(1L)).thenReturn(List.of(appliances));
    }

    private void forecast(String source, Instant from, Map<String, Double> overrides) {
        when(forecasts.forecast()).thenReturn(PriceForecastSource.Lookup.of(new PriceForecast(source, "test", now,
                CheapestStartCalculatorTest.forecast(from, 48, 300, overrides))));
    }

    private static Map<String, Double> cheapAt13() {
        return Map.of("13:00", 20.0, "13:30", 20.0);
    }

    private Recommendation onlyInsert() {
        ArgumentCaptor<Recommendation> saved = ArgumentCaptor.forClass(Recommendation.class);
        verify(repository).insert(saved.capture());
        return saved.getValue();
    }

    private Recommendation open(long id, long applianceId, String saving) {
        return new Recommendation(id, 1L, applianceId, "Open one", "SHIFT_START", sg("2026-09-30T13:00"),
                sg("2026-09-30T19:45"), new BigDecimal("0.10"), new BigDecimal("0.80"), new BigDecimal(saving),
                "reason", PriceForecast.DEMO, "demo", RecommendationStatus.ACTIVE, null, now, null);
    }

    private static CheapestStartCalculator.Result result(String bestStart, String saving) {
        return new CheapestStartCalculator.Result(CheapestStartCalculator.Outcome.SHIFT, sg("2026-09-30T19:45"),
                new BigDecimal("1.00"), sg(bestStart), new BigDecimal("0.50"), new BigDecimal(saving));
    }

    private static Instant sg(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(SINGAPORE).toInstant();
    }

    private static HouseholdResponse household(boolean exposed) {
        return new HouseholdResponse(1L, "The Tan Household", DwellingType.HDB_4_ROOM, 4,
                exposed ? PlanType.PRICE_LINKED : PlanType.FIXED, exposed ? null : new BigDecimal("29.5"),
                null, exposed, exposed ? "Price-linked (USEP)" : "Not exposed (fixed rate)", false, null);
    }

    private static ApplianceResponse appliance(long id, String name, String kw, int minutes, String earliest,
                                               String finishBy, String usual) {
        return new ApplianceResponse(id, 1L, name, ApplianceType.TUMBLE_DRYER, new BigDecimal(kw), minutes,
                new BigDecimal(kw), Flexibility.FLEXIBLE, LocalTime.parse(earliest), LocalTime.parse(finishBy),
                LocalTime.parse(usual), 840, false, 3, true, null);
    }
}

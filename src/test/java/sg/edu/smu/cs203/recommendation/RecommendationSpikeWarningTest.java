package sg.edu.smu.cs203.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
 * CSDT4-38: "avoid this time" warnings when a spike is forecast during an appliance's usual run,
 * the same guards and ranking as other suggestions, and Got it / Remind me later. Fixed Clock.
 */
class RecommendationSpikeWarningTest {

    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
    private final Instant now = sg("2026-09-30T08:10");

    private final RecommendationRepository repository = mock(RecommendationRepository.class);
    private final HouseholdDirectory households = mock(HouseholdDirectory.class);
    private final PriceForecastSource forecasts = mock(PriceForecastSource.class);
    private final PriceFeedStatus feed = mock(PriceFeedStatus.class);
    private final RecommendationService service = serviceAt(now);

    // ------------------------------------------------------------ creating warnings

    @Test
    void aSpikeAtTheUsualTimeGivesAnAvoidWarningWithTheCostAvoided() {
        priceLinked(dryer());
        forecast(PriceForecast.DEMO, Map.of("20:00", 3000.0), Set.of("20:00"));

        service.generate(1L);

        Recommendation warning = onlyInsert();
        assertThat(warning.kind()).isEqualTo(Recommendation.AVOID);
        assertThat(warning.usualStart()).isEqualTo(sg("2026-09-30T19:45"));
        assertThat(warning.spikeStart()).isEqualTo(sg("2026-09-30T20:00"));
        assertThat(warning.suggestedStart()).isEqualTo(sg("2026-09-30T08:30"));
        assertThat(warning.estSaving()).isEqualByComparingTo("3.375");
        assertThat(warning.snoozedUntil()).isNull();
        assertThat(warning.reason())
                .startsWith("A price spike is forecast from 20:00, during your usual 19:45 run. "
                        + "Start at 08:30 instead to avoid about $3.38 on this run ($0.75 vs $4.13).")
                .contains("demo forecast");
    }

    @Test
    void aWarningReplacesTheShiftSuggestionForThatAppliance() {
        priceLinked(dryer());
        forecast(PriceForecast.DEMO, Map.of("13:00", 20.0, "13:30", 20.0, "20:00", 3000.0), Set.of("20:00"));
        when(repository.findActive(1L)).thenReturn(List.of(shift(5L)));
        when(repository.closeActive(5L, RecommendationStatus.SUPERSEDED, now, null)).thenReturn(true);

        service.generate(1L);

        verify(repository).logEvent(5L, "SUPERSEDED", now, null, false);
        assertThat(onlyInsert().kind()).isEqualTo(Recommendation.AVOID);
    }

    @Test
    void warningsAreRankedWithSuggestionsAndCountTowardsTheMaxOfThree() {
        // Dryer (spike at its usual time) avoids $4.08; Washer saves $0.56, Heater $0.28, Small $0.14.
        priceLinked(dryer(),
                appliance(11, "Washer", "2.0", "18:00"),
                appliance(12, "Heater", "1.0", "18:00"),
                appliance(13, "Small", "0.5", "18:00"));
        forecast(PriceForecast.DEMO, Map.of("13:00", 20.0, "13:30", 20.0, "20:00", 3000.0), Set.of("20:00"));

        service.generate(1L);

        ArgumentCaptor<Recommendation> saved = ArgumentCaptor.forClass(Recommendation.class);
        verify(repository, times(3)).insert(saved.capture());
        assertThat(saved.getAllValues()).extracting(Recommendation::applianceName)
                .containsExactly("Tumble Dryer", "Washer", "Heater");
        assertThat(saved.getAllValues()).extracting(Recommendation::kind)
                .containsExactly(Recommendation.AVOID, Recommendation.SHIFT_START, Recommendation.SHIFT_START);
    }

    @Test
    void aDismissedShiftStillGetsAWarningBecauseTheyRunAtTheUsualTime() {
        priceLinked(dryer());
        forecast(PriceForecast.DEMO, Map.of("20:00", 3000.0), Set.of("20:00"));
        when(repository.decidedForRun(10L, sg("2026-09-30T19:45"))).thenReturn(true);

        service.generate(1L);

        assertThat(onlyInsert().kind()).isEqualTo(Recommendation.AVOID);
    }

    @Test
    void aRunWhoseWarningWasAcknowledgedIsNotWarnedAgain() {
        priceLinked(dryer());
        forecast(PriceForecast.DEMO, Map.of("20:00", 3000.0), Set.of("20:00"));
        when(repository.movedOrAcknowledgedForRun(10L, sg("2026-09-30T19:45"))).thenReturn(true);

        service.generate(1L);

        verify(repository, never()).insert(any());
    }

    // ------------------------------------------------------------ same guards as other suggestions

    @Test
    void fixedRateHouseholdsGetNoWarnings() {
        when(households.household(1L)).thenReturn(household(false));
        when(households.flexibleAppliances(1L)).thenReturn(List.of(dryer()));
        forecast(PriceForecast.F3, Map.of("20:00", 3000.0), Set.of("20:00"));

        service.generate(1L);

        verify(forecasts, never()).forecast();
        verify(repository, never()).insert(any());
    }

    @Test
    void aStaleFeedBlocksWarningsFromF3() {
        priceLinked(dryer());
        forecast(PriceForecast.F3, Map.of("20:00", 3000.0), Set.of("20:00"));
        when(feed.stale()).thenReturn(true);

        RecommendationsResponse response = service.generate(1L);

        assertThat(response.message()).isEqualTo(RecommendationService.STALE_FEED_MESSAGE);
        verify(repository, never()).insert(any());
    }

    @Test
    void aSpikeOutsideTheTimeWindowIsIgnored() {
        // The dryer must finish by 21:00, so a 21:30 spike can never touch its run.
        priceLinked(dryer());
        forecast(PriceForecast.DEMO, Map.of("21:30", 3000.0), Set.of("21:30"));

        service.generate(1L);

        verify(repository, never()).insert(any());
    }

    // ------------------------------------------------------------ Got it

    @Test
    void gotItAcknowledgesTheWarningAndLogsIt() {
        when(repository.findById(7L)).thenReturn(Optional.of(warning(7L, sg("2026-09-30T19:45"), null)));
        when(repository.closeActive(7L, RecommendationStatus.ACKNOWLEDGED, now, null)).thenReturn(true);

        service.gotIt(7L);

        verify(repository).closeActive(7L, RecommendationStatus.ACKNOWLEDGED, now, null);
        verify(repository).logEvent(eq(7L), eq("ACKNOWLEDGED"), eq(now), isNull(), eq(false));
    }

    @Test
    void gotItIsOnlyForWarningsAndAcceptIsOnlyForShifts() {
        when(repository.findById(5L)).thenReturn(Optional.of(shift(5L)));
        when(repository.findById(7L)).thenReturn(Optional.of(warning(7L, sg("2026-09-30T19:45"), null)));

        assertThatThrownBy(() -> service.gotIt(5L)).isInstanceOf(RecommendationActionNotAllowedException.class);
        assertThatThrownBy(() -> service.remindLater(5L)).isInstanceOf(RecommendationActionNotAllowedException.class);
        assertThatThrownBy(() -> service.accept(7L, null)).isInstanceOf(RecommendationActionNotAllowedException.class);
        assertThatThrownBy(() -> service.dismiss(7L)).isInstanceOf(RecommendationActionNotAllowedException.class);
        verify(repository, never()).closeActive(anyLong(), any(), any(), any());
    }

    // ------------------------------------------------------------ Remind me later

    @Test
    void remindMeLaterHidesTheWarningForAnHour() {
        when(repository.findById(7L)).thenReturn(Optional.of(warning(7L, sg("2026-09-30T19:45"), null)));
        when(repository.snooze(7L, sg("2026-09-30T09:10"))).thenReturn(true);

        service.remindLater(7L);

        verify(repository).snooze(7L, sg("2026-09-30T09:10"));
        verify(repository).logEvent(7L, "SNOOZED", now, sg("2026-09-30T09:10"), false);
    }

    @Test
    void remindMeLaterComesBackAnHourBeforeTheUsualStartIfThatIsSooner() {
        // Usual start 09:40, so an hour before it (08:40) is sooner than an hour from now (09:10).
        when(repository.findById(7L)).thenReturn(Optional.of(warning(7L, sg("2026-09-30T09:40"), null)));
        when(repository.snooze(7L, sg("2026-09-30T08:40"))).thenReturn(true);

        service.remindLater(7L);

        verify(repository).snooze(7L, sg("2026-09-30T08:40"));
    }

    @Test
    void remindMeLaterIsRefusedWhenTheUsualStartIsUnderAnHourAway() {
        when(repository.findById(7L)).thenReturn(Optional.of(warning(7L, sg("2026-09-30T09:00"), null)));

        assertThatThrownBy(() -> service.remindLater(7L))
                .isInstanceOf(RecommendationActionNotAllowedException.class)
                .hasMessageContaining("less than an hour away");
        verify(repository, never()).snooze(anyLong(), any());
    }

    @Test
    void aSnoozedWarningIsHiddenUntilItsTimeThenShownAgain() {
        when(households.household(1L)).thenReturn(household(true));
        when(repository.findActive(1L)).thenReturn(
                List.of(warning(7L, sg("2026-09-30T19:45"), sg("2026-09-30T09:10")), shift(5L)));

        assertThat(service.list(1L).recommendations()).extracting(RecommendationResponse::id).containsExactly(5L);
        assertThat(serviceAt(sg("2026-09-30T09:10")).list(1L).recommendations())
                .extracting(RecommendationResponse::id).containsExactly(7L, 5L);
    }

    @Test
    void anUpdatedWarningForTheSameRunKeepsRemindMeLater() {
        priceLinked(dryer());
        forecast(PriceForecast.DEMO, Map.of("20:00", 3000.0), Set.of("20:00"));
        // The open warning suggested 15:00; the new forecast makes 08:30 the best spike-free start.
        Recommendation snoozed = new Recommendation(7L, 1L, 10L, "Tumble Dryer", Recommendation.AVOID,
                sg("2026-09-30T15:00"), sg("2026-09-30T19:45"), new BigDecimal("0.75"), new BigDecimal("4.125"),
                new BigDecimal("3.375"), "reason", PriceForecast.DEMO, "demo", RecommendationStatus.ACTIVE, null,
                now, null, sg("2026-09-30T20:00"), sg("2026-09-30T09:10"));
        when(repository.findActive(1L)).thenReturn(List.of(snoozed));
        when(repository.closeActive(7L, RecommendationStatus.SUPERSEDED, now, null)).thenReturn(true);

        service.generate(1L);

        assertThat(onlyInsert().snoozedUntil()).isEqualTo(sg("2026-09-30T09:10"));
    }

    // ------------------------------------------------------------ helpers

    private RecommendationService serviceAt(Instant at) {
        return new RecommendationService(repository, households, forecasts, feed, Clock.fixed(at, SINGAPORE),
                new BigDecimal("0.05"), 3, false);
    }

    private void priceLinked(ApplianceResponse... appliances) {
        when(households.household(1L)).thenReturn(household(true));
        when(households.flexibleAppliances(1L)).thenReturn(List.of(appliances));
    }

    /** 48 half-hours from 08:30 at 300 SGD/MWh, with overrides and spike flags by time of day. */
    private void forecast(String source, Map<String, Double> overrides, Set<String> spikes) {
        List<PriceForecast.HalfHourPrice> points = new ArrayList<>();
        for (PriceForecast.HalfHourPrice p : CheapestStartCalculatorTest.forecast(
                sg("2026-09-30T08:30"), 48, 300, overrides)) {
            String time = p.periodStart().atZone(SINGAPORE).toLocalTime().toString();
            points.add(new PriceForecast.HalfHourPrice(p.periodStart(), p.usepSgdPerMwh(), spikes.contains(time)));
        }
        when(forecasts.forecast()).thenReturn(PriceForecastSource.Lookup.of(
                new PriceForecast(source, "test", now, points)));
    }

    private Recommendation onlyInsert() {
        ArgumentCaptor<Recommendation> saved = ArgumentCaptor.forClass(Recommendation.class);
        verify(repository).insert(saved.capture());
        return saved.getValue();
    }

    private Recommendation shift(long id) {
        return new Recommendation(id, 1L, 10L, "Tumble Dryer", Recommendation.SHIFT_START, sg("2026-09-30T13:00"),
                sg("2026-09-30T19:45"), new BigDecimal("0.05"), new BigDecimal("0.75"), new BigDecimal("0.70"),
                "reason", PriceForecast.DEMO, "demo", RecommendationStatus.ACTIVE, null, now, null);
    }

    private Recommendation warning(long id, Instant usualStart, Instant snoozedUntil) {
        return new Recommendation(id, 1L, 11L, "Washer", Recommendation.AVOID, sg("2026-09-30T08:30"),
                usualStart, new BigDecimal("0.75"), new BigDecimal("4.125"), new BigDecimal("3.375"),
                "reason", PriceForecast.DEMO, "demo", RecommendationStatus.ACTIVE, null, now, null,
                usualStart.plusSeconds(900), snoozedUntil);
    }

    private static Instant sg(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(SINGAPORE).toInstant();
    }

    private static HouseholdResponse household(boolean exposed) {
        return new HouseholdResponse(1L, "The Tan Household", DwellingType.HDB_4_ROOM, 4,
                exposed ? PlanType.PRICE_LINKED : PlanType.FIXED, exposed ? null : new BigDecimal("29.5"),
                null, exposed, exposed ? "Price-linked (USEP)" : "Not exposed (fixed rate)", false, null);
    }

    private static ApplianceResponse dryer() {
        return appliance(10, "Tumble Dryer", "2.5", "19:45");
    }

    private static ApplianceResponse appliance(long id, String name, String kw, String usual) {
        return new ApplianceResponse(id, 1L, name, ApplianceType.TUMBLE_DRYER, new BigDecimal(kw), 60,
                new BigDecimal(kw), Flexibility.FLEXIBLE, LocalTime.of(7, 0), LocalTime.of(21, 0),
                LocalTime.parse(usual), 840, false, 3, true, null);
    }
}

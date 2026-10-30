package sg.edu.smu.cs203.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import sg.edu.smu.cs203.household.DwellingType;
import sg.edu.smu.cs203.household.HouseholdDirectory;
import sg.edu.smu.cs203.household.HouseholdResponse;
import sg.edu.smu.cs203.household.PlanType;
import sg.edu.smu.cs203.household.ValidationFailedException;
import sg.edu.smu.cs203.household.appliance.ApplianceResponse;
import sg.edu.smu.cs203.household.appliance.ApplianceType;
import sg.edu.smu.cs203.household.appliance.Flexibility;

/** CSDT4-40: tabs on the Recommendations page and "Remind me later" until a chosen time. Fixed Clock. */
class RecommendationTabsAndSnoozeTest {

    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
    private final Instant now = sg("2026-09-30T08:10");

    private final RecommendationRepository repository = mock(RecommendationRepository.class);
    private final HouseholdDirectory households = mock(HouseholdDirectory.class);
    private final PriceForecastSource forecasts = mock(PriceForecastSource.class);
    private final RecommendationService service = new RecommendationService(repository, households, org.mockito.Mockito.mock(
            sg.edu.smu.cs203.household.HouseholdRecommendationReader.class), forecasts,
            mock(PriceFeedStatus.class), Clock.fixed(now, SINGAPORE), new BigDecimal("0.05"), 3, false);

    // ------------------------------------------------------------ tabs

    @Test
    void theActiveTabIncludesSnoozedSuggestionsButTheDashboardDoesNot() {
        when(households.household(1L)).thenReturn(household());
        when(households.flexibleAppliances(1L)).thenReturn(List.of(dryer()));
        when(repository.findActive(1L)).thenReturn(List.of(shift(5L, sg("2026-09-30T13:00"), sg("2026-09-30T10:00")),
                shift(6L, sg("2026-09-30T14:00"), null)));

        RecommendationsResponse tab = service.list(1L, RecommendationTab.ACTIVE);
        RecommendationsResponse dashboard = service.list(1L);

        assertThat(tab.recommendations()).extracting(RecommendationResponse::id).containsExactly(5L, 6L);
        assertThat(tab.recommendations().getFirst().remindAt()).isEqualTo(sg("2026-09-30T10:00"));
        assertThat(tab.recommendations().getFirst().runMinutes()).isEqualTo(60);
        assertThat(dashboard.recommendations()).extracting(RecommendationResponse::id).containsExactly(6L);
    }

    @Test
    void decidedTabsAskForTheirStatusesOverTheLast30Days() {
        when(households.household(1L)).thenReturn(household());
        Instant since = sg("2026-08-31T08:10");

        service.list(1L, RecommendationTab.ACCEPTED);
        service.list(1L, RecommendationTab.DISMISSED);
        service.list(1L, RecommendationTab.HISTORY);

        verify(repository).findByStatuses(1L, List.of(RecommendationStatus.ACCEPTED), since, 200);
        verify(repository).findByStatuses(1L,
                List.of(RecommendationStatus.DISMISSED, RecommendationStatus.ACKNOWLEDGED), since, 200);
        verify(repository).findByStatuses(1L, List.of(RecommendationStatus.values()), since, 200);
    }

    // ------------------------------------------------------------ snooze until a chosen time

    @Test
    void snoozeHidesASuggestionUntilTheChosenTimeToday() {
        when(repository.findById(5L)).thenReturn(Optional.of(shift(5L, sg("2026-09-30T19:00"), null)));
        when(repository.snooze(5L, sg("2026-09-30T15:30"))).thenReturn(true);

        service.snooze(5L, new SnoozeRequest("15:30"));

        verify(repository).snooze(5L, sg("2026-09-30T15:30"));
        verify(repository).logEvent(5L, "SNOOZED", now, sg("2026-09-30T15:30"), false);
    }

    @Test
    void aTimeThatHasPassedTodayMeansTomorrowAndMustStillBeBeforeTheSuggestionRunsOut() {
        // 07:00 has passed, so it means 07:00 tomorrow: after the 13:00 suggestion is gone.
        when(repository.findById(5L)).thenReturn(Optional.of(shift(5L, sg("2026-09-30T13:00"), null)));

        assertThatThrownBy(() -> service.snooze(5L, new SnoozeRequest("07:00")))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.errors())
                        .containsEntry("until", "Pick a time before 1:00 PM, when this suggestion runs out"));
        assertThatThrownBy(() -> service.snooze(5L, new SnoozeRequest("13:00")))
                .isInstanceOf(ValidationFailedException.class);
        verify(repository, never()).snooze(anyLong(), any());
    }

    @Test
    void aWarningMustComeBackBeforeTheEarlierOfItsAlternativeAndUsualStart() {
        // Alternative 13:00, usual 19:45: after 13:00 the warning expires, so 14:00 is too late.
        Recommendation warning = new Recommendation(7L, 1L, 10L, "Tumble Dryer", Recommendation.AVOID,
                sg("2026-09-30T13:00"), sg("2026-09-30T19:45"), new BigDecimal("0.05"), new BigDecimal("4.1"),
                new BigDecimal("4.05"), "reason", PriceForecast.DEMO, "demo", RecommendationStatus.ACTIVE, null,
                now, null, sg("2026-09-30T20:00"), null);
        when(repository.findById(7L)).thenReturn(Optional.of(warning));
        when(repository.snooze(7L, sg("2026-09-30T12:00"))).thenReturn(true);

        assertThatThrownBy(() -> service.snooze(7L, new SnoozeRequest("14:00")))
                .isInstanceOf(ValidationFailedException.class);
        service.snooze(7L, new SnoozeRequest("12:00"));
        verify(repository).snooze(7L, sg("2026-09-30T12:00"));
    }

    @Test
    void theOneTapRemindLaterNowWorksOnShiftSuggestionsToo() {
        when(repository.findById(5L)).thenReturn(Optional.of(shift(5L, sg("2026-09-30T19:00"), null)));
        when(repository.snooze(5L, sg("2026-09-30T09:10"))).thenReturn(true);

        service.remindLater(5L);

        verify(repository).snooze(5L, sg("2026-09-30T09:10"));
    }

    @Test
    void nextAtRollsOverMidnight() {
        assertThat(RecommendationService.nextAt(LocalTime.of(8, 30), now)).isEqualTo(sg("2026-09-30T08:30"));
        assertThat(RecommendationService.nextAt(LocalTime.of(8, 10), now)).isEqualTo(sg("2026-10-01T08:10"));
        assertThat(RecommendationService.nextAt(LocalTime.of(1, 0), now)).isEqualTo(sg("2026-10-01T01:00"));
    }

    // ------------------------------------------------------------ the demo forecast for the charts

    @Test
    void theDemoForecastIsGivenForChartsOnlyWhileItIsInUse() {
        PriceForecast demo = new PriceForecast(PriceForecast.DEMO, "Replay day", now,
                CheapestStartCalculatorTest.forecast(sg("2026-09-30T08:30"), 48, 300, Map.of()),
                LocalDate.of(2026, 9, 26));
        when(forecasts.forecast()).thenReturn(PriceForecastSource.Lookup.of(demo));

        DemoForecastResponse response = service.demoForecast();

        assertThat(response.replayDay()).isEqualTo(LocalDate.of(2026, 9, 26));
        assertThat(response.points()).hasSize(48);
        assertThat(response.points().getFirst().periodStart()).isEqualTo(sg("2026-09-30T08:30"));

        when(forecasts.forecast()).thenReturn(PriceForecastSource.Lookup.of(
                new PriceForecast(PriceForecast.F3, "B2", now, demo.points())));
        assertThatThrownBy(service::demoForecast).isInstanceOf(DemoForecastNotInUseException.class);
    }

    // ------------------------------------------------------------ helpers

    private Recommendation shift(long id, Instant suggestedStart, Instant snoozedUntil) {
        return new Recommendation(id, 1L, 10L, "Tumble Dryer", Recommendation.SHIFT_START, suggestedStart,
                sg("2026-09-30T19:45"), new BigDecimal("0.05"), new BigDecimal("0.75"), new BigDecimal("0.70"),
                "reason", PriceForecast.DEMO, "demo", RecommendationStatus.ACTIVE, null, now, null, null, snoozedUntil);
    }

    private static Instant sg(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(SINGAPORE).toInstant();
    }

    private static HouseholdResponse household() {
        return new HouseholdResponse(1L, "The Tan Household", DwellingType.HDB_4_ROOM, 4, PlanType.PRICE_LINKED,
                null, null, true, "Price-linked (USEP)", false, null);
    }

    private static ApplianceResponse dryer() {
        return new ApplianceResponse(10L, 1L, "Tumble Dryer", ApplianceType.TUMBLE_DRYER, new BigDecimal("2.5"), 60,
                new BigDecimal("2.5"), Flexibility.FLEXIBLE, LocalTime.of(7, 0), LocalTime.of(21, 0),
                LocalTime.of(19, 45), 840, false, 3, true, null);
    }
}

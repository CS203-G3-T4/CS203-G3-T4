package sg.edu.smu.cs203.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import sg.edu.smu.cs203.household.DwellingType;
import sg.edu.smu.cs203.household.HouseholdDirectory;
import sg.edu.smu.cs203.household.HouseholdResponse;
import sg.edu.smu.cs203.household.PlanType;
import sg.edu.smu.cs203.household.ValidationFailedException;
import sg.edu.smu.cs203.household.appliance.ApplianceResponse;
import sg.edu.smu.cs203.household.appliance.ApplianceType;
import sg.edu.smu.cs203.household.appliance.Flexibility;

class RecommendationServiceTest {

    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
    private final Instant now = sg("2026-09-30T08:10");

    private final RecommendationRepository repository = mock(RecommendationRepository.class);
    private final HouseholdDirectory households = mock(HouseholdDirectory.class);
    private final PriceForecastSource forecasts = mock(PriceForecastSource.class);
    private final RecommendationService service = new RecommendationService(repository, households, forecasts,
            Clock.fixed(now, SINGAPORE), new BigDecimal("0.01"));

    @Test
    void generateSavesAnActiveShiftSuggestionWithItsSavingAndReason() {
        when(households.household(1L)).thenReturn(household(true));
        when(households.flexibleAppliances(1L)).thenReturn(List.of(dryer()));
        when(forecasts.forecast()).thenReturn(PriceForecastSource.Lookup.of(demoForecast()));
        when(repository.insert(any())).thenReturn(7L);

        service.generate(1L);

        ArgumentCaptor<Recommendation> saved = ArgumentCaptor.forClass(Recommendation.class);
        verify(repository).insert(saved.capture());
        Recommendation row = saved.getValue();
        assertThat(row.kind()).isEqualTo("SHIFT_START");
        assertThat(row.status()).isEqualTo(RecommendationStatus.ACTIVE);
        assertThat(row.suggestedStart()).isEqualTo(sg("2026-09-30T13:00"));
        assertThat(row.usualStart()).isEqualTo(sg("2026-09-30T19:45"));
        assertThat(row.estSaving()).isPositive();
        assertThat(row.forecastSource()).isEqualTo(PriceForecast.DEMO);
        assertThat(row.reason()).startsWith("Start at 13:00 instead of 19:45").contains("demo forecast");
        verify(repository).logEvent(eq(7L), eq("CREATED"), eq(now), eq(sg("2026-09-30T13:00")), eq(false));
        verify(repository).lockHousehold(1L);
    }

    @Test
    void aChangedForecastReplacesTheOldSuggestion() {
        when(households.household(1L)).thenReturn(household(true));
        when(households.flexibleAppliances(1L)).thenReturn(List.of(dryer()));
        when(forecasts.forecast()).thenReturn(PriceForecastSource.Lookup.of(demoForecast()));
        when(repository.findActive(1L)).thenReturn(List.of(active(5L, sg("2026-09-30T10:00"))));
        when(repository.closeActive(5L, RecommendationStatus.SUPERSEDED, now, null)).thenReturn(true);
        when(repository.insert(any())).thenReturn(8L);

        service.generate(1L);

        verify(repository).logEvent(5L, "SUPERSEDED", now, null, false);
        verify(repository).insert(any());
    }

    @Test
    void anUnchangedSuggestionIsKeptRatherThanDuplicated() {
        when(households.household(1L)).thenReturn(household(true));
        when(households.flexibleAppliances(1L)).thenReturn(List.of(dryer()));
        when(forecasts.forecast()).thenReturn(PriceForecastSource.Lookup.of(demoForecast()));
        when(repository.findActive(1L)).thenReturn(List.of(active(5L, sg("2026-09-30T13:00"))));

        service.generate(1L);

        verify(repository, never()).insert(any());
        verify(repository, never()).closeActive(anyLong(), any(), any(), any());
    }

    @Test
    void aRunTheResidentAlreadyDecidedIsNotSuggestedAgain() {
        when(households.household(1L)).thenReturn(household(true));
        when(households.flexibleAppliances(1L)).thenReturn(List.of(dryer()));
        when(forecasts.forecast()).thenReturn(PriceForecastSource.Lookup.of(demoForecast()));
        when(repository.decidedForRun(10L, sg("2026-09-30T19:45"))).thenReturn(true);

        service.generate(1L);

        verify(repository, never()).insert(any());
    }

    @Test
    void fixedRateHouseholdsGetNoSuggestions() {
        when(households.household(1L)).thenReturn(household(false));

        RecommendationsResponse response = service.generate(1L);

        assertThat(response.message()).contains("fixed-rate");
        verify(forecasts, never()).forecast();
        verify(repository, never()).insert(any());
    }

    @Test
    void withoutAUsableForecastNothingIsSuggestedAndThePageIsToldWhy() {
        when(households.household(1L)).thenReturn(household(true));
        when(forecasts.forecast()).thenReturn(PriceForecastSource.Lookup.unavailable("F3's forecast is stale"));

        RecommendationsResponse response = service.generate(1L);

        assertThat(response.message()).contains("F3's forecast is stale");
        verify(repository, never()).insert(any());
    }

    @Test
    void acceptSavesStatusDecidedAtAndLogsTheDecision() {
        when(repository.findById(5L)).thenReturn(Optional.of(active(5L, sg("2026-09-30T13:00"))));
        when(repository.closeActive(5L, RecommendationStatus.ACCEPTED, now, sg("2026-09-30T13:00"))).thenReturn(true);

        service.accept(5L, null);

        verify(repository).closeActive(5L, RecommendationStatus.ACCEPTED, now, sg("2026-09-30T13:00"));
        verify(repository).logEvent(5L, "ACCEPTED", now, sg("2026-09-30T13:00"), false);
    }

    @Test
    void acceptCanUseTheResidentsOwnTimeInsideTheWindow() {
        when(repository.findById(5L)).thenReturn(Optional.of(active(5L, sg("2026-09-30T13:00"))));
        when(households.flexibleAppliances(1L)).thenReturn(List.of(dryer()));
        when(repository.closeActive(5L, RecommendationStatus.ACCEPTED, now, sg("2026-09-30T15:10"))).thenReturn(true);

        service.accept(5L, new AcceptRequest("15:10"));

        verify(repository).logEvent(5L, "ACCEPTED", now, sg("2026-09-30T15:10"), true);
    }

    @Test
    void anOverrideThatWouldFinishAfterTheWindowIsRejected() {
        when(repository.findById(5L)).thenReturn(Optional.of(active(5L, sg("2026-09-30T13:00"))));
        when(households.flexibleAppliances(1L)).thenReturn(List.of(dryer()));

        assertThatThrownBy(() -> service.accept(5L, new AcceptRequest("20:30")))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.errors())
                        .containsEntry("startTime", "Pick a time from 07:00 to 20:00 that hasn't passed yet"));
        verify(repository, never()).closeActive(anyLong(), any(), any(), any());
    }

    @Test
    void dismissSavesStatusAndLogsTheDecision() {
        when(repository.findById(5L)).thenReturn(Optional.of(active(5L, sg("2026-09-30T13:00"))));
        when(repository.closeActive(5L, RecommendationStatus.DISMISSED, now, null)).thenReturn(true);

        service.dismiss(5L);

        verify(repository).logEvent(eq(5L), eq("DISMISSED"), eq(now), isNull(), eq(false));
    }

    @Test
    void aDecidedSuggestionCannotBeDecidedAgain() {
        Recommendation accepted = new Recommendation(5L, 1L, 10L, "Tumble Dryer", "SHIFT_START",
                sg("2026-09-30T13:00"), sg("2026-09-30T19:45"), BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ONE,
                "reason", PriceForecast.DEMO, "demo", RecommendationStatus.ACCEPTED, sg("2026-09-30T13:00"),
                now, now);
        when(repository.findById(5L)).thenReturn(Optional.of(accepted));

        assertThatThrownBy(() -> service.dismiss(5L)).isInstanceOf(RecommendationNotActiveException.class);
        verify(repository, never()).logEvent(anyLong(), anyString(), any(), any(), anyBoolean());
    }

    @Test
    void anUnknownSuggestionIsNotFound() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.accept(99L, null)).isInstanceOf(RecommendationNotFoundException.class);
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
        return new ApplianceResponse(10L, 1L, "Tumble Dryer", ApplianceType.TUMBLE_DRYER, new BigDecimal("2.5"), 60,
                new BigDecimal("2.5"), Flexibility.FLEXIBLE, LocalTime.of(7, 0), LocalTime.of(21, 0),
                LocalTime.of(19, 45), 840, false, 3, true, null);
    }

    private Recommendation active(long id, Instant suggestedStart) {
        return new Recommendation(id, 1L, 10L, "Tumble Dryer", "SHIFT_START", suggestedStart,
                sg("2026-09-30T19:45"), new BigDecimal("0.10"), new BigDecimal("0.80"), new BigDecimal("0.70"),
                "reason", PriceForecast.DEMO, "demo", RecommendationStatus.ACTIVE, null, now, null);
    }

    /** 300 SGD/MWh all day except a cheap 13:00-14:00. */
    private PriceForecast demoForecast() {
        return new PriceForecast(PriceForecast.DEMO, "Replay day test", now,
                CheapestStartCalculatorTest.forecast(sg("2026-09-30T08:30"), 48, 300,
                        Map.of("13:00", 20.0, "13:30", 20.0)));
    }
}

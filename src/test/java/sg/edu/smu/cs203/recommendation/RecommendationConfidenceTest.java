package sg.edu.smu.cs203.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
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

/** CSDT4-39: each saved suggestion carries its confidence from actual past prices. Fixed Clock. */
class RecommendationConfidenceTest {

    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
    private final Instant now = sg("2026-09-30T08:10");

    private final RecommendationRepository repository = mock(RecommendationRepository.class);
    private final HouseholdDirectory households = mock(HouseholdDirectory.class);
    private final PriceForecastSource forecasts = mock(PriceForecastSource.class);
    private final RecommendationService service = new RecommendationService(repository, households, org.mockito.Mockito.mock(
            sg.edu.smu.cs203.household.HouseholdRecommendationReader.class), forecasts,
            mock(PriceFeedStatus.class), Clock.fixed(now, SINGAPORE), new BigDecimal("0.05"), 3, false);
    private final List<Instant> askedAsOf = new ArrayList<>();

    @Test
    void anF3SuggestionIsJudgedOnTheLast28DaysBeforeNow() {
        setUp(null, cheapAt13Before(sg("2026-09-30T00:00")));

        service.generate(1L);

        Recommendation saved = onlyInsert();
        assertThat(askedAsOf).containsExactly(now);
        assertThat(saved.confidenceCheaperDays()).isEqualTo(28);
        assertThat(saved.confidenceDaysChecked()).isEqualTo(28);
        assertThat(RecommendationResponse.from(saved(saved)).confidencePercent()).isEqualTo(100);
    }

    @Test
    void aDemoSuggestionIsJudgedOnTheFourWeeksBeforeItsReplayDay() {
        // Prices only exist up to 26 Sep (the replay day). Judging from 30 Sep would lose 4 days.
        setUp(LocalDate.of(2026, 9, 26), cheapAt13Before(sg("2026-09-26T00:00")));

        service.generate(1L);

        Recommendation saved = onlyInsert();
        assertThat(askedAsOf).containsExactly(sg("2026-09-26T00:00"));
        assertThat(saved.confidenceDaysChecked()).isEqualTo(28);
    }

    @Test
    void withoutPriceHistoryThereIsNoConfidenceAndTheSavingIsEstimated() {
        setUp(null, Map.of());

        service.generate(1L);

        RecommendationResponse response = RecommendationResponse.from(saved(onlyInsert()));
        assertThat(response.confidencePercent()).isNull();
        assertThat(response.confidenceDaysChecked()).isNull();
        assertThat(response.savingEstimated()).isTrue();
    }

    private void setUp(LocalDate replayDay, Map<Instant, BigDecimal> actual) {
        service.setPastPrices(asOf -> {
            askedAsOf.add(asOf);
            return actual;
        });
        when(households.household(1L)).thenReturn(household());
        when(households.flexibleAppliances(1L)).thenReturn(List.of(dryer()));
        String source = replayDay == null ? PriceForecast.F3 : PriceForecast.DEMO;
        when(forecasts.forecast()).thenReturn(PriceForecastSource.Lookup.of(new PriceForecast(source, "B2", now,
                CheapestStartCalculatorTest.forecast(sg("2026-09-30T08:30"), 48, 300,
                        Map.of("13:00", 20.0, "13:30", 20.0)), replayDay)));
        when(repository.insert(any())).thenReturn(7L);
    }

    /** 300 SGD/MWh all day and 100 from 13:00-14:00, for the 28 days before the given midnight. */
    private static Map<Instant, BigDecimal> cheapAt13Before(Instant midnight) {
        Map<Instant, BigDecimal> prices = new HashMap<>();
        for (Instant t = midnight.minus(Duration.ofDays(28)); t.isBefore(midnight); t = t.plusSeconds(1800)) {
            prices.put(t, BigDecimal.valueOf(t.atZone(SINGAPORE).getHour() == 13 ? 100 : 300));
        }
        return prices;
    }

    private Recommendation onlyInsert() {
        ArgumentCaptor<Recommendation> saved = ArgumentCaptor.forClass(Recommendation.class);
        verify(repository).insert(saved.capture());
        return saved.getValue();
    }

    /** The row as read back after insert (with its id). */
    private static Recommendation saved(Recommendation r) {
        return new Recommendation(7L, r.householdId(), r.applianceId(), r.applianceName(), r.kind(),
                r.suggestedStart(), r.usualStart(), r.suggestedCost(), r.usualCost(), r.estSaving(), r.reason(),
                r.forecastSource(), r.forecastModel(), r.status(), r.acceptedStart(), r.createdAt(), r.decidedAt(),
                r.spikeStart(), r.snoozedUntil(), r.confidenceCheaperDays(), r.confidenceDaysChecked());
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

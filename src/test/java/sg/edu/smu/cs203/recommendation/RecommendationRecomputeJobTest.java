package sg.edu.smu.cs203.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import sg.edu.smu.cs203.common.DemoClock;
import sg.edu.smu.cs203.household.DwellingType;
import sg.edu.smu.cs203.household.HouseholdChanged;
import sg.edu.smu.cs203.household.HouseholdDirectory;
import sg.edu.smu.cs203.household.HouseholdNotFoundException;
import sg.edu.smu.cs203.household.HouseholdResponse;
import sg.edu.smu.cs203.household.PlanType;
import sg.edu.smu.cs203.market.price.MarketObservationSaved;

class RecommendationRecomputeJobTest {

    private final RecommendationService service = mock(RecommendationService.class);
    private final HouseholdDirectory households = mock(HouseholdDirectory.class);
    private final DemoClock clock = new DemoClock(Clock.fixed(
            Instant.parse("2026-10-24T08:00:00Z"), ZoneId.of("Asia/Singapore")));
    private final RecommendationRecomputeJob job = new RecommendationRecomputeJob(service, households, clock, true, "demo");

    @Test
    void aNewPriceRecomputesEveryRealHousehold() {
        when(households.households()).thenReturn(List.of(household(1L), household(3L)));

        job.onNewPrice(new MarketObservationSaved());

        verify(service).generateInBackground(1L);
        verify(service).generateInBackground(3L);
    }

    @Test
    void oneFailingHouseholdDoesNotStopTheOthers() {
        when(households.households()).thenReturn(List.of(household(1L), household(3L)));
        doThrow(new IllegalStateException("database hiccup")).when(service).generateInBackground(1L);

        assertThat(job.recomputeAll("test")).isEqualTo(1);
        verify(service).generateInBackground(3L);
    }

    @Test
    void anApplianceChangeRecomputesOnlyThatHousehold() {
        job.onHouseholdChanged(new HouseholdChanged(4L));

        verify(service).generateInBackground(4L);
        verify(households, never()).households();
    }

    @Test
    void aHouseholdDeletedInTheMeantimeIsIgnored() {
        doThrow(new HouseholdNotFoundException(4L)).when(service).generateInBackground(4L);

        job.onHouseholdChanged(new HouseholdChanged(4L));

        verify(service).generateInBackground(4L);
    }

    @Test
    void turnedOffItRecomputesNothing() {
        RecommendationRecomputeJob off = new RecommendationRecomputeJob(service, households, clock, false, "demo");

        off.every30Minutes();
        off.onHouseholdChanged(new HouseholdChanged(4L));

        verify(service, never()).generateInBackground(anyLong());
    }

    @Test
    void f3WaitsForTheCommittedLiveForecastInsteadOfThePriceEvent() {
        var live = new RecommendationRecomputeJob(service, households, clock, true, "f3");
        when(households.households()).thenReturn(List.of(household(1L), household(3L)));
        live.onNewPrice(new MarketObservationSaved());
        live.onForecastSaved(new sg.edu.smu.cs203.forecast.ForecastSaved("replay", "REPLAY"));
        verify(service, never()).generateInBackground(anyLong());
        live.onForecastSaved(new sg.edu.smu.cs203.forecast.ForecastSaved("saved", "LIVE"));
        verify(service).generateInBackground(1L);
        verify(service).generateInBackground(3L);
    }

    @Test
    void automaticRecomputationIsPausedDuringDemoReplay() {
        clock.startAt(Instant.parse("2026-09-26T00:00:00Z"));
        when(households.households()).thenReturn(List.of(household(1L)));

        assertThat(job.recomputeAll("demo test")).isZero();
        job.onHouseholdChanged(new HouseholdChanged(4L));

        verify(service, never()).generateInBackground(anyLong());
        verify(households, never()).households();
    }

    private static HouseholdResponse household(long id) {
        return new HouseholdResponse(id, "Household " + id, DwellingType.HDB_4_ROOM, 4, PlanType.PRICE_LINKED,
                null, null, true, "Price-linked (USEP)", false, null);
    }
}

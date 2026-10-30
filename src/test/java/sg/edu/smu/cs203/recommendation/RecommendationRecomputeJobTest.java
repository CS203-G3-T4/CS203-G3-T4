package sg.edu.smu.cs203.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
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
    private final RecommendationRecomputeJob job = new RecommendationRecomputeJob(service, households, clock, true);

    @Test
    void aNewPriceRecomputesEveryRealHousehold() {
        when(households.households()).thenReturn(List.of(household(1L), household(3L)));

        job.onNewPrice(new MarketObservationSaved());

        verify(service).generate(1L);
        verify(service).generate(3L);
    }

    @Test
    void oneFailingHouseholdDoesNotStopTheOthers() {
        when(households.households()).thenReturn(List.of(household(1L), household(3L)));
        when(service.generate(1L)).thenThrow(new IllegalStateException("database hiccup"));

        assertThat(job.recomputeAll("test")).isEqualTo(1);
        verify(service).generate(3L);
    }

    @Test
    void anApplianceChangeRecomputesOnlyThatHousehold() {
        job.onHouseholdChanged(new HouseholdChanged(4L));

        verify(service).generate(4L);
        verify(households, never()).households();
    }

    @Test
    void aHouseholdDeletedInTheMeantimeIsIgnored() {
        when(service.generate(4L)).thenThrow(new HouseholdNotFoundException(4L));

        job.onHouseholdChanged(new HouseholdChanged(4L));

        verify(service).generate(4L);
    }

    @Test
    void turnedOffItRecomputesNothing() {
        RecommendationRecomputeJob off = new RecommendationRecomputeJob(service, households, clock, false);

        off.every30Minutes();
        off.onHouseholdChanged(new HouseholdChanged(4L));

        verify(service, never()).generate(anyLong());
    }

    @Test
    void automaticRecomputationIsPausedDuringDemoReplay() {
        clock.startAt(Instant.parse("2026-09-26T00:00:00Z"));
        when(households.households()).thenReturn(List.of(household(1L)));

        assertThat(job.recomputeAll("demo test")).isZero();
        job.onHouseholdChanged(new HouseholdChanged(4L));

        verify(service, never()).generate(anyLong());
        verify(households, never()).households();
    }

    private static HouseholdResponse household(long id) {
        return new HouseholdResponse(id, "Household " + id, DwellingType.HDB_4_ROOM, 4, PlanType.PRICE_LINKED,
                null, null, true, "Price-linked (USEP)", false, null);
    }
}

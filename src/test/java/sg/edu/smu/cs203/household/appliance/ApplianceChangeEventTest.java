package sg.edu.smu.cs203.household.appliance;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import sg.edu.smu.cs203.household.HouseholdChanged;
import sg.edu.smu.cs203.household.HouseholdService;

/** CSDT4-37: F4 recomputes suggestions when an appliance changes, so F2 announces each change. */
class ApplianceChangeEventTest {

    private final Instant now = Instant.parse("2026-09-30T06:00:00Z");
    private final ApplianceRepository appliances = mock(ApplianceRepository.class);
    private final HouseholdService households = mock(HouseholdService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ApplianceService service = new ApplianceService(appliances, households, Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void addingAnApplianceAnnouncesTheChange() {
        service.setEvents(events);
        when(appliances.insert(any())).thenReturn(10L);
        when(appliances.findById(1L, 10L)).thenReturn(Optional.of(dryer()));

        service.create(1L, request());

        verify(events).publishEvent(new HouseholdChanged(1L));
    }

    @Test
    void deletingAnApplianceAnnouncesTheChange() {
        service.setEvents(events);
        when(appliances.delete(1L, 10L)).thenReturn(true);

        service.delete(1L, 10L);

        verify(events).publishEvent(new HouseholdChanged(1L));
    }

    @Test
    void withoutAPublisherNothingBreaks() {
        when(appliances.delete(1L, 10L)).thenReturn(true);

        service.delete(1L, 10L);
    }

    private static ApplianceRequest request() {
        return new ApplianceRequest("Tumble Dryer", ApplianceType.TUMBLE_DRYER, new BigDecimal("2.5"), 60,
                Flexibility.FLEXIBLE, LocalTime.of(7, 0), LocalTime.of(21, 0), LocalTime.of(19, 45), 3, true, null);
    }

    private Appliance dryer() {
        return new Appliance(10L, 1L, "Tumble Dryer", ApplianceType.TUMBLE_DRYER, new BigDecimal("2.5"), 60,
                Flexibility.FLEXIBLE, LocalTime.of(7, 0), LocalTime.of(21, 0), LocalTime.of(19, 45), 3, true, null,
                now, now);
    }
}

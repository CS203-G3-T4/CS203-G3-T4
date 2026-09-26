package sg.edu.smu.cs203.household.appliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import sg.edu.smu.cs203.household.HouseholdNotFoundException;
import sg.edu.smu.cs203.household.HouseholdService;
import sg.edu.smu.cs203.household.ValidationFailedException;

class ApplianceServiceTest {

    private final Instant now = Instant.parse("2026-09-26T06:00:00Z");
    private final ApplianceRepository appliances = mock(ApplianceRepository.class);
    private final HouseholdService households = mock(HouseholdService.class);
    private final ApplianceService service = new ApplianceService(
            appliances, households, Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void createSavesTheApplianceWithDefaultsAndTheClockTime() {
        ApplianceRequest request = new ApplianceRequest("  Tumble Dryer ",
                ApplianceType.TUMBLE_DRYER, new BigDecimal("2.5"), 60, Flexibility.FLEXIBLE,
                LocalTime.of(7, 0), LocalTime.of(21, 0), null, null, null, " ");
        when(appliances.insert(any())).thenReturn(10L);
        when(appliances.findById(1L, 10L)).thenReturn(Optional.of(dryer(10L)));

        ApplianceResponse created = service.create(1L, request);

        ArgumentCaptor<Appliance> saved = ArgumentCaptor.forClass(Appliance.class);
        verify(appliances).insert(saved.capture());
        assertThat(saved.getValue().name()).isEqualTo("Tumble Dryer");
        assertThat(saved.getValue().runsPerWeek()).isEqualTo(7);
        assertThat(saved.getValue().enabled()).isTrue();
        assertThat(saved.getValue().notes()).isNull();
        assertThat(saved.getValue().createdAt()).isEqualTo(now);
        assertThat(created.energyPerRunKwh()).isEqualByComparingTo("2.5");
    }

    @Test
    void createRejectsAWindowTooShortForTheRunWithoutSaving() {
        ApplianceRequest request = new ApplianceRequest("Dryer", ApplianceType.TUMBLE_DRYER,
                new BigDecimal("2.5"), 90, Flexibility.FLEXIBLE,
                LocalTime.of(8, 0), LocalTime.of(9, 0), null, 3, true, null);

        assertThatThrownBy(() -> service.create(1L, request))
                .isInstanceOf(ValidationFailedException.class)
                .satisfies(e -> assertThat(((ValidationFailedException) e).errors())
                        .containsKey("mustFinishBy"));
        verify(appliances, never()).insert(any());
    }

    @Test
    void unknownHouseholdIsReportedBeforeAnythingElse() {
        doThrow(new HouseholdNotFoundException(99L)).when(households).requireExists(99L);

        assertThatThrownBy(() -> service.list(99L)).isInstanceOf(HouseholdNotFoundException.class);
    }

    @Test
    void applianceFromAnotherHouseholdIsNotFound() {
        when(appliances.findById(2L, 10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(2L, 10L))
                .isInstanceOf(ApplianceNotFoundException.class);
    }

    @Test
    void switchingOffKeepsTheOriginalCreationTime() {
        Appliance existing = dryer(10L);
        when(appliances.findById(1L, 10L)).thenReturn(Optional.of(existing));
        when(appliances.update(any())).thenReturn(true);
        ApplianceRequest switchOff = new ApplianceRequest("Tumble Dryer",
                ApplianceType.TUMBLE_DRYER, new BigDecimal("2.5"), 60, Flexibility.FLEXIBLE,
                LocalTime.of(7, 0), LocalTime.of(21, 0), LocalTime.of(19, 45), 3, false, null);

        service.update(1L, 10L, switchOff);

        ArgumentCaptor<Appliance> saved = ArgumentCaptor.forClass(Appliance.class);
        verify(appliances).update(saved.capture());
        assertThat(saved.getValue().enabled()).isFalse();
        assertThat(saved.getValue().createdAt()).isEqualTo(existing.createdAt());
        assertThat(saved.getValue().updatedAt()).isEqualTo(now);
    }

    @Test
    void deletingAMissingApplianceIsNotFound() {
        when(appliances.delete(1L, 404L)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(1L, 404L))
                .isInstanceOf(ApplianceNotFoundException.class);
    }

    @Test
    void listMapsEveryAppliance() {
        when(appliances.findByHousehold(1L)).thenReturn(List.of(dryer(10L), dryer(11L)));

        assertThat(service.list(1L)).extracting(ApplianceResponse::id).containsExactly(10L, 11L);
    }

    private Appliance dryer(long id) {
        return new Appliance(id, 1L, "Tumble Dryer", ApplianceType.TUMBLE_DRYER,
                new BigDecimal("2.500"), 60, Flexibility.FLEXIBLE, LocalTime.of(7, 0),
                LocalTime.of(21, 0), LocalTime.of(19, 45), 3, true, null,
                Instant.parse("2026-09-20T00:00:00Z"), Instant.parse("2026-09-20T00:00:00Z"));
    }
}

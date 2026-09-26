package sg.edu.smu.cs203.household.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import sg.edu.smu.cs203.household.DwellingType;
import sg.edu.smu.cs203.household.Household;
import sg.edu.smu.cs203.household.HouseholdNotFoundException;
import sg.edu.smu.cs203.household.HouseholdRepository;
import sg.edu.smu.cs203.household.PlanType;
import sg.edu.smu.cs203.household.appliance.ApplianceRepository;

class LoadProfileServiceTest {

    // 23 Sep 2026 17:00 UTC is already 24 Sep 01:00 in Singapore.
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-23T17:00:00Z"),
            ZoneId.of("Asia/Singapore"));
    private final HouseholdRepository households = mock(HouseholdRepository.class);
    private final ApplianceRepository appliances = mock(ApplianceRepository.class);
    private final LoadArchetypeRepository archetypes = mock(LoadArchetypeRepository.class);
    private final LoadProfileService service =
            new LoadProfileService(households, appliances, archetypes, clock);

    @Test
    void defaultsToTodayInSingaporeAndReturns48Slots() {
        when(households.findById(1L)).thenReturn(Optional.of(new Household(1L, "Tans",
                DwellingType.HDB_4_ROOM, 4, PlanType.PRICE_LINKED, null, 3L, false,
                Instant.EPOCH, Instant.EPOCH)));
        when(archetypes.findById(3L)).thenReturn(Optional.of(new LoadArchetype(3L, "HDB_4_ROOM",
                DwellingType.HDB_4_ROOM, "4-room HDB flat", new BigDecimal("380.7"),
                new BigDecimal("1.10"), "EMA 2024")));
        when(appliances.findByHousehold(1L)).thenReturn(List.of());

        LoadProfileResponse profile = service.profile(1L, null);

        assertThat(profile.date()).isEqualTo(LocalDate.of(2026, 9, 24));
        assertThat(profile.slots()).hasSize(48);
        assertThat(profile.modelled()).isTrue();
    }

    @Test
    void unknownHouseholdIsNotFound() {
        when(households.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.profile(99L, LocalDate.of(2026, 9, 24)))
                .isInstanceOf(HouseholdNotFoundException.class);
    }
}

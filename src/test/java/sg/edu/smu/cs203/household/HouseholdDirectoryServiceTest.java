package sg.edu.smu.cs203.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import sg.edu.smu.cs203.household.appliance.ApplianceService;
import sg.edu.smu.cs203.household.load.LoadProfileService;

class HouseholdDirectoryServiceTest {

    private final HouseholdService households = mock(HouseholdService.class);
    private final HouseholdRepository householdRepository = mock(HouseholdRepository.class);
    private final ApplianceService appliances = mock(ApplianceService.class);
    private final LoadProfileService loadProfiles = mock(LoadProfileService.class);
    private final HouseholdDirectoryService directory = new HouseholdDirectoryService(
            households, householdRepository, appliances, loadProfiles);

    @Test
    void listsRealHouseholdsForInternalBackgroundWorkWithoutAUserSession() {
        when(householdRepository.findAll(false)).thenReturn(List.of(
                new Household(1L, "Home", DwellingType.HDB_4_ROOM, 4, PlanType.PRICE_LINKED,
                        null, 1L, false, Instant.EPOCH, Instant.EPOCH),
                new Household(2L, "Office", DwellingType.HDB_4_ROOM, 4, PlanType.FIXED,
                        null, 1L, false, Instant.EPOCH, Instant.EPOCH)));

        List<HouseholdResponse> result = directory.households();

        assertThat(result).extracting(HouseholdResponse::id).containsExactly(1L, 2L);
        verify(householdRepository).findAll(false);
        verifyNoInteractions(households);
    }
}

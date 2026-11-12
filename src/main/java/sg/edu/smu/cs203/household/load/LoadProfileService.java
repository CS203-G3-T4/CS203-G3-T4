package sg.edu.smu.cs203.household.load;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;

import org.springframework.stereotype.Service;

import sg.edu.smu.cs203.household.Household;
import sg.edu.smu.cs203.household.HouseholdNotFoundException;
import sg.edu.smu.cs203.household.HouseholdOwnershipService;
import sg.edu.smu.cs203.household.HouseholdRepository;
import sg.edu.smu.cs203.household.appliance.Appliance;
import sg.edu.smu.cs203.household.appliance.ApplianceRepository;

@Service
public class LoadProfileService {

    private final HouseholdRepository households;
    private final ApplianceRepository appliances;
    private final LoadArchetypeRepository archetypes;
    private final HouseholdOwnershipService ownership;
    private final Clock clock;

    public LoadProfileService(HouseholdRepository households, ApplianceRepository appliances,
                              LoadArchetypeRepository archetypes,
                              HouseholdOwnershipService ownership, Clock clock) {
        this.households = households;
        this.appliances = appliances;
        this.archetypes = archetypes;
        this.ownership = ownership;
        this.clock = clock;
    }

    /** Modelled half-hourly use for a date; a null date means today in Singapore. */
    public LoadProfileResponse profile(long householdId, LocalDate date) {
        LocalDate day = date;
        if (day == null) {
            day = LocalDate.now(clock);
        }
        return profiler(householdId).apply(day);
    }

    /**
     * Day-to-profile function for a household. Access check, archetype and appliances are
     * loaded once here, so callers that need many days do not repeat those queries per day.
     */
    public Function<LocalDate, LoadProfileResponse> profiler(long householdId) {
        Household household = households.findById(householdId)
                .orElseThrow(() -> new HouseholdNotFoundException(householdId));
        ownership.requireAccess(householdId);
        LoadArchetype archetype = archetypeFor(household);
        List<Appliance> owned = appliances.findByHousehold(householdId);
        return day -> LoadProfileResponse.from(householdId, archetype,
                LoadProfileCalculator.calculate(archetype, owned, day));
    }

    public List<LoadArchetype> archetypes() {
        return archetypes.findAll();
    }

    private LoadArchetype archetypeFor(Household household) {
        if (household.archetypeId() != null) {
            return archetypes.findById(household.archetypeId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Archetype " + household.archetypeId() + " is missing"));
        }
        return archetypes.findByDwellingType(household.dwellingType())
                .orElseThrow(() -> new IllegalStateException(
                        "No archetype for " + household.dwellingType()));
    }
}

package sg.edu.smu.cs203.household.load;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;

import sg.edu.smu.cs203.household.Household;
import sg.edu.smu.cs203.household.HouseholdNotFoundException;
import sg.edu.smu.cs203.household.HouseholdRepository;
import sg.edu.smu.cs203.household.appliance.Appliance;
import sg.edu.smu.cs203.household.appliance.ApplianceRepository;

@Service
public class LoadProfileService {

    private final HouseholdRepository households;
    private final ApplianceRepository appliances;
    private final LoadArchetypeRepository archetypes;
    private final Clock clock;

    public LoadProfileService(HouseholdRepository households, ApplianceRepository appliances,
                              LoadArchetypeRepository archetypes, Clock clock) {
        this.households = households;
        this.appliances = appliances;
        this.archetypes = archetypes;
        this.clock = clock;
    }

    /** Modelled half-hourly use for a date; a null date means today in Singapore. */
    public LoadProfileResponse profile(long householdId, LocalDate date) {
        LocalDate day = date;
        if (day == null) {
            day = LocalDate.now(clock);
        }
        Household household = households.findById(householdId)
                .orElseThrow(() -> new HouseholdNotFoundException(householdId));
        LoadArchetype archetype = archetypeFor(household);
        List<Appliance> owned = appliances.findByHousehold(householdId);
        LoadProfileCalculator.DailyLoad load =
                LoadProfileCalculator.calculate(archetype, owned, day);
        return LoadProfileResponse.from(householdId, archetype, load);
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

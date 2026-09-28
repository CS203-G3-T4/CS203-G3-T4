package sg.edu.smu.cs203.household;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import sg.edu.smu.cs203.household.appliance.ApplianceResponse;
import sg.edu.smu.cs203.household.appliance.ApplianceService;
import sg.edu.smu.cs203.household.appliance.Flexibility;
import sg.edu.smu.cs203.household.load.LoadProfileResponse;
import sg.edu.smu.cs203.household.load.LoadProfileService;

@Service
public class HouseholdDirectoryService implements HouseholdDirectory {

    private final HouseholdService households;
    private final ApplianceService appliances;
    private final LoadProfileService loadProfiles;

    public HouseholdDirectoryService(HouseholdService households, ApplianceService appliances,
                                     LoadProfileService loadProfiles) {
        this.households = households;
        this.appliances = appliances;
        this.loadProfiles = loadProfiles;
    }

    @Override
    public HouseholdResponse household(long householdId) {
        return households.get(householdId);
    }

    @Override
    public List<ApplianceResponse> flexibleAppliances(long householdId) {
        List<ApplianceResponse> flexible = new ArrayList<>();
        for (ApplianceResponse appliance : appliances.list(householdId)) {
            if (appliance.enabled() && appliance.flexibility() == Flexibility.FLEXIBLE) {
                flexible.add(appliance);
            }
        }
        return flexible;
    }

    @Override
    public LoadProfileResponse loadProfile(long householdId, LocalDate date) {
        return loadProfiles.profile(householdId, date);
    }
}

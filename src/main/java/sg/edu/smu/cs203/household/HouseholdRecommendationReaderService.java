package sg.edu.smu.cs203.household;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sg.edu.smu.cs203.household.appliance.ApplianceRepository;
import sg.edu.smu.cs203.household.appliance.ApplianceResponse;
import sg.edu.smu.cs203.household.appliance.Flexibility;

/** Reads only real household recommendation inputs, without impersonating a resident or admin. */
@Service
public class HouseholdRecommendationReaderService implements HouseholdRecommendationReader {
    private final HouseholdRepository households;
    private final ApplianceRepository appliances;

    public HouseholdRecommendationReaderService(HouseholdRepository households, ApplianceRepository appliances) {
        this.households = households;
        this.appliances = appliances;
    }

    @Override
    @Transactional(readOnly = true)
    public Inputs read(long householdId) {
        Household household = households.findById(householdId)
                .filter(h -> !h.simulated())
                .orElseThrow(() -> new HouseholdNotFoundException(householdId));
        var flexible = appliances.findByHousehold(householdId).stream()
                .filter(a -> a.enabled() && a.flexibility() == Flexibility.FLEXIBLE)
                .map(ApplianceResponse::from).toList();
        return new Inputs(HouseholdResponse.from(household), flexible);
    }
}

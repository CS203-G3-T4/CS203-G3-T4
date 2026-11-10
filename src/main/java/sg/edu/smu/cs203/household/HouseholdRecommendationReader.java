package sg.edu.smu.cs203.household;

import java.util.List;
import sg.edu.smu.cs203.household.appliance.ApplianceResponse;

/** Internal, read-only F2 hand-off for the recommendation worker; never a resident API. */
public interface HouseholdRecommendationReader {
    Inputs read(long householdId);

    record Inputs(HouseholdResponse household, List<ApplianceResponse> flexibleAppliances) {
        public Inputs {
            flexibleAppliances = List.copyOf(flexibleAppliances);
        }
    }
}

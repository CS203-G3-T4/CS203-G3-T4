package sg.edu.smu.cs203.household;

import java.math.BigDecimal;
import java.time.Instant;

/** What the pages receive. Fixed-rate households are flagged as not exposed to USEP. */
public record HouseholdResponse(
        Long id,
        String name,
        DwellingType dwellingType,
        int occupants,
        PlanType planType,
        BigDecimal fixedRateCentsPerKwh,
        Long archetypeId,
        boolean exposedToWholesalePrice,
        String exposureLabel,
        boolean simulated,
        Instant updatedAt) {

    public static HouseholdResponse from(Household household) {
        boolean exposed = household.exposedToWholesalePrice();
        String label;
        if (exposed) {
            label = "Price-linked (USEP)";
        } else {
            label = "Not exposed (fixed rate)";
        }
        return new HouseholdResponse(
                household.id(),
                household.name(),
                household.dwellingType(),
                household.occupants(),
                household.planType(),
                household.fixedRateCentsPerKwh(),
                household.archetypeId(),
                exposed,
                label,
                household.simulated(),
                household.updatedAt());
    }
}

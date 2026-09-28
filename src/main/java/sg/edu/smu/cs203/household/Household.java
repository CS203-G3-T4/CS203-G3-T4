package sg.edu.smu.cs203.household;

import java.math.BigDecimal;
import java.time.Instant;

public record Household(
        Long id,
        String name,
        DwellingType dwellingType,
        int occupants,
        PlanType planType,
        BigDecimal fixedRateCentsPerKwh,
        Long archetypeId,
        boolean simulated,
        Instant createdAt,
        Instant updatedAt) {

    /** Only price-linked households are exposed to USEP, so only they get shifting advice. */
    public boolean exposedToWholesalePrice() {
        return planType == PlanType.PRICE_LINKED;
    }
}

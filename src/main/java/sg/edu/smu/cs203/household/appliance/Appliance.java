package sg.edu.smu.cs203.household.appliance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;

public record Appliance(
        Long id,
        long householdId,
        String name,
        ApplianceType type,
        BigDecimal powerKw,
        int runMinutes,
        Flexibility flexibility,
        LocalTime earliestStart,
        LocalTime mustFinishBy,
        LocalTime usualStart,
        int runsPerWeek,
        boolean enabled,
        String notes,
        Instant createdAt,
        Instant updatedAt) {
}

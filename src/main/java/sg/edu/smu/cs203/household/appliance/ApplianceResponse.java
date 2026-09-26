package sg.edu.smu.cs203.household.appliance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;

/**
 * What the pages receive. energyPerRunKwh = powerKw x runMinutes / 60, so the page never has
 * to mix up kW (power) and kWh (energy). windowMinutes is null unless the appliance is flexible.
 */
public record ApplianceResponse(
        long id,
        long householdId,
        String name,
        ApplianceType type,
        BigDecimal powerKw,
        int runMinutes,
        BigDecimal energyPerRunKwh,
        Flexibility flexibility,
        LocalTime earliestStart,
        LocalTime mustFinishBy,
        LocalTime usualStart,
        Integer windowMinutes,
        boolean windowCrossesMidnight,
        int runsPerWeek,
        boolean enabled,
        String notes) {

    public static ApplianceResponse from(Appliance appliance) {
        BigDecimal energy = appliance.powerKw()
                .multiply(BigDecimal.valueOf(appliance.runMinutes()))
                .divide(BigDecimal.valueOf(60), 3, RoundingMode.HALF_UP);

        Integer windowMinutes = null;
        boolean crossesMidnight = false;
        if (appliance.flexibility() == Flexibility.FLEXIBLE
                && appliance.earliestStart() != null && appliance.mustFinishBy() != null) {
            TimeWindow window = TimeWindow.of(appliance.earliestStart(), appliance.mustFinishBy());
            windowMinutes = window.lengthMinutes();
            crossesMidnight = window.crossesMidnight();
        }

        return new ApplianceResponse(
                appliance.id(),
                appliance.householdId(),
                appliance.name(),
                appliance.type(),
                appliance.powerKw(),
                appliance.runMinutes(),
                energy,
                appliance.flexibility(),
                appliance.earliestStart(),
                appliance.mustFinishBy(),
                appliance.usualStart(),
                windowMinutes,
                crossesMidnight,
                appliance.runsPerWeek(),
                appliance.enabled(),
                appliance.notes());
    }
}

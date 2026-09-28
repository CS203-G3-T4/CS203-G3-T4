package sg.edu.smu.cs203.household.load;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A household's modelled consumption for one day, in kWh per half-hour.
 * "modelled" is always true: Wattly has no smart-meter data.
 */
public record LoadProfileResponse(
        long householdId,
        LocalDate date,
        String archetype,
        BigDecimal archetypeMonthlyKwh,
        boolean modelled,
        BigDecimal totalKwh,
        BigDecimal baseKwh,
        BigDecimal applianceKwh,
        BigDecimal evKwh,
        List<Slot> slots,
        List<String> notes) {

    public record Slot(LocalTime start, BigDecimal baseKwh, BigDecimal applianceKwh,
                       BigDecimal evKwh, BigDecimal totalKwh) {
    }

    public static LoadProfileResponse from(long householdId, LoadArchetype archetype,
                                           LoadProfileCalculator.DailyLoad load) {
        List<Slot> slots = new ArrayList<>();
        for (int slot = 0; slot < DailyLoadShape.SLOTS_PER_DAY; slot++) {
            double base = load.baseKwh()[slot];
            double appliances = load.applianceKwh()[slot];
            double ev = load.evKwh()[slot];
            slots.add(new Slot(LocalTime.MIDNIGHT.plusMinutes(30L * slot), kwh(base),
                    kwh(appliances), kwh(ev), kwh(base + appliances + ev)));
        }
        List<String> notes = new ArrayList<>();
        notes.add("Modelled, not metered: calibrated to EMA's average for a "
                + archetype.label() + " (" + archetype.monthlyKwh() + " kWh/month).");
        notes.addAll(load.notes());
        return new LoadProfileResponse(
                householdId,
                load.date(),
                archetype.label(),
                archetype.monthlyKwh(),
                true,
                kwh(load.totalKwh()),
                kwh(load.total(load.baseKwh())),
                kwh(load.total(load.applianceKwh())),
                kwh(load.total(load.evKwh())),
                slots,
                notes);
    }

    private static BigDecimal kwh(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }
}

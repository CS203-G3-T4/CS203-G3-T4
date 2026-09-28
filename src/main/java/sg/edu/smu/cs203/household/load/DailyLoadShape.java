package sg.edu.smu.cs203.household.load;

/**
 * How a household's base load (everything we don't model as a separate appliance) is spread
 * over the 48 half-hours of a day. The values are relative weights, not kWh.
 *
 * ASSUMPTION, not measured data: a typical Singapore home with bedroom aircon at night,
 * a morning bump, a quiet weekday midday while people are out, and an evening peak.
 * Weekends are busier at midday. Replace these with a published load profile if the team
 * finds one; the monthly total stays calibrated to EMA either way.
 */
public final class DailyLoadShape {

    public static final int SLOTS_PER_DAY = 48;

    // One value per hour, 00:00 to 23:00. Each hour covers two half-hour slots.
    private static final double[] WEEKDAY_BY_HOUR = {
            1.00, 0.95, 0.90, 0.85, 0.85, 0.85, 0.95, 1.05, 0.85, 0.65, 0.60, 0.60,
            0.65, 0.60, 0.60, 0.62, 0.68, 0.80, 1.00, 1.25, 1.35, 1.35, 1.25, 1.10};

    private static final double[] WEEKEND_BY_HOUR = {
            1.00, 0.95, 0.90, 0.85, 0.85, 0.85, 0.85, 0.90, 0.95, 0.95, 0.95, 1.00,
            1.05, 1.05, 1.05, 1.00, 0.95, 0.95, 1.05, 1.25, 1.30, 1.30, 1.25, 1.10};

    private DailyLoadShape() {
    }

    /** Fractions for each half-hour slot that add up to exactly 1. */
    public static double[] fractions(boolean weekend) {
        double[] byHour = WEEKDAY_BY_HOUR;
        if (weekend) {
            byHour = WEEKEND_BY_HOUR;
        }
        double total = 0;
        for (double value : byHour) {
            total = total + value * 2;
        }
        double[] slots = new double[SLOTS_PER_DAY];
        for (int slot = 0; slot < SLOTS_PER_DAY; slot++) {
            slots[slot] = byHour[slot / 2] / total;
        }
        return slots;
    }
}

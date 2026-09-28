package sg.edu.smu.cs203.household.load;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import sg.edu.smu.cs203.household.appliance.Appliance;
import sg.edu.smu.cs203.household.appliance.ApplianceType;
import sg.edu.smu.cs203.household.appliance.Flexibility;

/**
 * Builds a household's modelled half-hourly consumption for one day.
 *
 * Method (documented on the "About our data" page):
 * 1. Start from the archetype's EMA monthly average for the home type.
 * 2. Subtract the monthly energy of the appliances we model one by one (so moving the dryer
 *    really changes the bill and nothing is counted twice). EV charging is NOT subtracted:
 *    EMA's averages don't include EVs, so it is added on top and reported separately.
 * 3. Share the remaining "base load" between the days of the month (weekend days get
 *    weekendFactor times a weekday), then across 48 half-hours using DailyLoadShape.
 * 4. Place each appliance's run at its usual start (fixed/flexible) or spread it evenly
 *    (not flexible). A run of 3 times a week counts as 3/7 of a run on every day, i.e. the
 *    expected load. Runs that pass midnight wrap to the start of the same modelled day.
 * Summed over a month, base + non-EV appliances equals the EMA figure exactly.
 */
public final class LoadProfileCalculator {

    private static final int SLOTS = DailyLoadShape.SLOTS_PER_DAY;
    private static final int MINUTES_PER_SLOT = 30;
    private static final int MINUTES_PER_DAY = 24 * 60;

    private LoadProfileCalculator() {
    }

    public static DailyLoad calculate(LoadArchetype archetype, List<Appliance> appliances,
                                      LocalDate date) {
        double monthlyTarget = archetype.monthlyKwh().doubleValue();
        double weekendFactor = archetype.weekendFactor().doubleValue();
        int daysInMonth = YearMonth.from(date).lengthOfMonth();

        double[] applianceKwh = new double[SLOTS];
        double[] evKwh = new double[SLOTS];
        double monthlyModelledApplianceKwh = 0;

        for (Appliance appliance : appliances) {
            if (!appliance.enabled()) {
                continue;
            }
            double runsPerDay = appliance.runsPerWeek() / 7.0;
            double kwhPerRun = appliance.powerKw().doubleValue() * appliance.runMinutes() / 60.0;
            boolean isEv = appliance.type() == ApplianceType.EV_CHARGER;
            if (!isEv) {
                monthlyModelledApplianceKwh += kwhPerRun * runsPerDay * daysInMonth;
            }
            double[] target = applianceKwh;
            if (isEv) {
                target = evKwh;
            }
            placeRun(appliance, runsPerDay, target);
        }

        List<String> notes = new ArrayList<>();
        double monthlyBase = monthlyTarget - monthlyModelledApplianceKwh;
        if (monthlyBase < 0) {
            notes.add("The listed appliances use more than the EMA average for this home type, "
                    + "so the base load is set to zero.");
            monthlyBase = 0;
        }

        double dayBase = monthlyBase * dayWeight(date, weekendFactor)
                / totalDayWeights(date, weekendFactor);
        double[] fractions = DailyLoadShape.fractions(isWeekend(date));
        double[] baseKwh = new double[SLOTS];
        for (int slot = 0; slot < SLOTS; slot++) {
            baseKwh[slot] = dayBase * fractions[slot];
        }
        return new DailyLoad(date, baseKwh, applianceKwh, evKwh, notes);
    }

    private static void placeRun(Appliance appliance, double runsPerDay, double[] target) {
        double powerKw = appliance.powerKw().doubleValue();
        if (appliance.flexibility() == Flexibility.NOT_FLEXIBLE) {
            // Runs whenever it needs to: spread the day's energy evenly.
            double dailyKwh = powerKw * appliance.runMinutes() / 60.0 * runsPerDay;
            for (int slot = 0; slot < SLOTS; slot++) {
                target[slot] += dailyKwh / SLOTS;
            }
            return;
        }
        LocalTime start = appliance.usualStart();
        if (start == null) {
            start = appliance.earliestStart();
        }
        if (start == null) {
            start = LocalTime.MIDNIGHT;
        }
        int startMinute = start.getHour() * 60 + start.getMinute();
        for (int minute = 0; minute < appliance.runMinutes(); minute++) {
            int minuteOfDay = (startMinute + minute) % MINUTES_PER_DAY;
            target[minuteOfDay / MINUTES_PER_SLOT] += powerKw / 60.0 * runsPerDay;
        }
    }

    static boolean isWeekend(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    private static double dayWeight(LocalDate date, double weekendFactor) {
        if (isWeekend(date)) {
            return weekendFactor;
        }
        return 1.0;
    }

    private static double totalDayWeights(LocalDate date, double weekendFactor) {
        YearMonth month = YearMonth.from(date);
        double total = 0;
        for (int day = 1; day <= month.lengthOfMonth(); day++) {
            total += dayWeight(month.atDay(day), weekendFactor);
        }
        return total;
    }

    /** Raw result in kWh per half-hour slot (slot 0 = 00:00–00:30). */
    public record DailyLoad(LocalDate date, double[] baseKwh, double[] applianceKwh,
                            double[] evKwh, List<String> notes) {

        public double total(double[] values) {
            double sum = 0;
            for (double value : values) {
                sum += value;
            }
            return sum;
        }

        public double totalKwh() {
            return total(baseKwh) + total(applianceKwh) + total(evKwh);
        }
    }
}

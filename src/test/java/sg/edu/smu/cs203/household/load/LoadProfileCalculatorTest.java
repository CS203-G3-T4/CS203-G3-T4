package sg.edu.smu.cs203.household.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.Test;

import sg.edu.smu.cs203.household.DwellingType;
import sg.edu.smu.cs203.household.appliance.Appliance;
import sg.edu.smu.cs203.household.appliance.ApplianceType;
import sg.edu.smu.cs203.household.appliance.Flexibility;

class LoadProfileCalculatorTest {

    private static final LoadArchetype FOUR_ROOM = new LoadArchetype(3L, "HDB_4_ROOM",
            DwellingType.HDB_4_ROOM, "4-room HDB flat", new BigDecimal("380.7"),
            new BigDecimal("1.10"), "EMA 2024");

    private final LocalDate weekday = LocalDate.of(2026, 9, 23); // Wednesday
    private final LocalDate saturday = LocalDate.of(2026, 9, 26);

    @Test
    void monthWithoutAppliancesAddsUpToTheEmaAverage() {
        assertThat(monthlyTotal(List.of(), false)).isCloseTo(380.7, within(0.001));
    }

    @Test
    void modelledAppliancesAreSubtractedSoTheMonthStillMatchesEma() {
        List<Appliance> appliances = List.of(dryer(true), fridge());

        // Base + non-EV appliances must equal EMA's figure (acceptance: within 2%).
        assertThat(monthlyTotal(appliances, false)).isCloseTo(380.7, within(0.001));
    }

    @Test
    void evChargingIsAddedOnTopAndReportedSeparately() {
        LoadProfileCalculator.DailyLoad load =
                LoadProfileCalculator.calculate(FOUR_ROOM, List.of(ev()), weekday);

        // 7.2 kW x 3 h x (3 runs / 7 days)
        assertThat(load.total(load.evKwh())).isCloseTo(7.2 * 3 * 3 / 7.0, within(1e-9));
        assertThat(monthlyTotal(List.of(ev()), false)).isCloseTo(380.7, within(0.001));
    }

    @Test
    void dryerEnergyLandsInTheHalfHoursItRuns() {
        LoadProfileCalculator.DailyLoad load =
                LoadProfileCalculator.calculate(FOUR_ROOM, List.of(dryer(true)), weekday);
        double perDay = 3 / 7.0;

        assertThat(load.applianceKwh()).hasSize(48);
        assertThat(load.applianceKwh()[39]).isCloseTo(2.5 * 0.25 * perDay, within(1e-9)); // 19:45-20:00
        assertThat(load.applianceKwh()[40]).isCloseTo(2.5 * 0.5 * perDay, within(1e-9));  // 20:00-20:30
        assertThat(load.applianceKwh()[41]).isCloseTo(2.5 * 0.25 * perDay, within(1e-9)); // 20:30-20:45
        assertThat(load.applianceKwh()[42]).isEqualTo(0.0);
    }

    @Test
    void switchedOffAppliancesAreIgnored() {
        LoadProfileCalculator.DailyLoad load =
                LoadProfileCalculator.calculate(FOUR_ROOM, List.of(dryer(false)), weekday);

        assertThat(load.total(load.applianceKwh())).isEqualTo(0.0);
    }

    @Test
    void weekendDaysUseMoreBaseLoadThanWeekdays() {
        double weekdayBase = base(weekday);
        double saturdayBase = base(saturday);

        assertThat(saturdayBase / weekdayBase).isCloseTo(1.10, within(1e-9));
    }

    @Test
    void appliancesAboveTheAverageSetBaseLoadToZeroWithANote() {
        Appliance hugeHeater = new Appliance(9L, 1L, "Heater", ApplianceType.OTHER,
                new BigDecimal("5.0"), 600, Flexibility.FIXED, null, null, LocalTime.of(8, 0),
                7, true, null, Instant.EPOCH, Instant.EPOCH);

        LoadProfileCalculator.DailyLoad load =
                LoadProfileCalculator.calculate(FOUR_ROOM, List.of(hugeHeater), weekday);

        assertThat(load.total(load.baseKwh())).isEqualTo(0.0);
        assertThat(load.notes()).hasSize(1);
    }

    @Test
    void shapeFractionsAddUpToOne() {
        double sum = 0;
        for (double fraction : DailyLoadShape.fractions(false)) {
            sum += fraction;
        }
        assertThat(sum).isCloseTo(1.0, within(1e-12));
    }

    private double base(LocalDate date) {
        LoadProfileCalculator.DailyLoad load =
                LoadProfileCalculator.calculate(FOUR_ROOM, List.of(), date);
        return load.total(load.baseKwh());
    }

    private double monthlyTotal(List<Appliance> appliances, boolean includeEv) {
        YearMonth month = YearMonth.from(weekday);
        double total = 0;
        for (int day = 1; day <= month.lengthOfMonth(); day++) {
            LoadProfileCalculator.DailyLoad load =
                    LoadProfileCalculator.calculate(FOUR_ROOM, appliances, month.atDay(day));
            total += load.total(load.baseKwh()) + load.total(load.applianceKwh());
            if (includeEv) {
                total += load.total(load.evKwh());
            }
        }
        return total;
    }

    private Appliance dryer(boolean enabled) {
        return new Appliance(1L, 1L, "Tumble Dryer", ApplianceType.TUMBLE_DRYER,
                new BigDecimal("2.5"), 60, Flexibility.FLEXIBLE, LocalTime.of(7, 0),
                LocalTime.of(21, 0), LocalTime.of(19, 45), 3, enabled, null,
                Instant.EPOCH, Instant.EPOCH);
    }

    private Appliance fridge() {
        return new Appliance(2L, 1L, "Refrigerator", ApplianceType.REFRIGERATOR,
                new BigDecimal("0.15"), 1440, Flexibility.NOT_FLEXIBLE, null, null, null,
                7, true, null, Instant.EPOCH, Instant.EPOCH);
    }

    private Appliance ev() {
        return new Appliance(3L, 1L, "EV Charger", ApplianceType.EV_CHARGER,
                new BigDecimal("7.2"), 180, Flexibility.FLEXIBLE, LocalTime.of(22, 0),
                LocalTime.of(7, 0), LocalTime.of(22, 30), 3, true, null,
                Instant.EPOCH, Instant.EPOCH);
    }
}

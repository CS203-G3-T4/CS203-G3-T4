package sg.edu.smu.cs203.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import sg.edu.smu.cs203.household.spend.SpendCalculator;
import sg.edu.smu.cs203.household.spend.TariffComponent;
import sg.edu.smu.cs203.household.spend.TariffComponent.Unit;

/** CSDT4-53: realised saving = cost at the usual time - cost at the chosen time, at known actual prices. */
class RealisedSavingsCalculatorTest {

    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
    /** A fixed "now": the morning after the runs below. */
    private static final Instant NOW = sg("2026-10-15T10:00");

    /** A 2 kW dryer for 60 minutes: 1 kWh in each half-hour. Usual 19:00, chosen 13:00 on 14 Oct. */
    private static final AcceptedRun DRYER = new AcceptedRun(5L, 10L, "Tumble Dryer", sg("2026-10-14T19:00"),
            sg("2026-10-14T13:00"), new BigDecimal("2.0"), 60, new BigDecimal("0.30"));

    private static final List<TariffComponent> CHARGES = List.of(
            charge("NETWORK", Unit.SGD_PER_KWH, "0.0500", null, null),
            charge("EVENING", Unit.SGD_PER_KWH, "0.0200", "17:00", "23:00"),
            charge("METER", Unit.SGD_PER_MONTH, "5.00", null, null),
            charge("GST", Unit.PERCENT, "9", null, null));

    @Test
    void savingIsTheUsualCostMinusTheChosenCostAtActualPricesWithChargesAndGst() {
        Map<Instant, BigDecimal> actual = prices(
                "2026-10-14T19:00", "300", "2026-10-14T19:30", "260",
                "2026-10-14T13:00", "120", "2026-10-14T13:30", "100");

        RealisedSavingsCalculator.Outcome outcome = RealisedSavingsCalculator.priceLinked(DRYER, actual, CHARGES, NOW);

        // Usual:  1 x (0.300 + 0.05 + 0.02) + 1 x (0.260 + 0.05 + 0.02) = 0.70, x 1.09 = 0.763 -> $0.76
        // Chosen: 1 x (0.120 + 0.05)        + 1 x (0.100 + 0.05)        = 0.32, x 1.09 = 0.3488 -> $0.35
        // The $5 monthly charge is the same whenever you run, so it is left out.
        assertThat(outcome.state()).isEqualTo(RealisedSavingsCalculator.State.REALISED);
        assertThat(outcome.usualCostSgd()).isEqualByComparingTo("0.76");
        assertThat(outcome.chosenCostSgd()).isEqualByComparingTo("0.35");
        assertThat(outcome.savingSgd()).isEqualByComparingTo("0.41");
        assertThat(outcome.pendingReason()).isNull();
    }

    @Test
    void aChosenTimeThatTurnedOutDearerGivesANegativeSaving() {
        Map<Instant, BigDecimal> actual = prices(
                "2026-10-14T19:00", "100", "2026-10-14T19:30", "100",
                "2026-10-14T13:00", "400", "2026-10-14T13:30", "400");

        RealisedSavingsCalculator.Outcome outcome = RealisedSavingsCalculator.priceLinked(DRYER, actual, CHARGES, NOW);

        // Usual 2 x (0.10 + 0.05 + 0.02) x 1.09 = 0.3706 -> $0.37; chosen 2 x 0.45 x 1.09 = 0.981 -> $0.98
        assertThat(outcome.savingSgd()).isEqualByComparingTo("-0.61");
    }

    @Test
    void aRunThatHasNotFinishedIsPendingAndNotGuessed() {
        Map<Instant, BigDecimal> actual = prices("2026-10-14T19:00", "300", "2026-10-14T19:30", "260",
                "2026-10-14T13:00", "120", "2026-10-14T13:30", "100");

        RealisedSavingsCalculator.Outcome outcome =
                RealisedSavingsCalculator.priceLinked(DRYER, actual, CHARGES, sg("2026-10-14T19:59"));

        assertThat(outcome.state()).isEqualTo(RealisedSavingsCalculator.State.PENDING);
        assertThat(outcome.savingSgd()).isNull();
        assertThat(outcome.pendingReason()).isEqualTo(RealisedSavingsCalculator.NOT_RUN_YET);
    }

    @Test
    void aMissingActualPriceKeepsItPending() {
        Map<Instant, BigDecimal> actual = prices("2026-10-14T19:00", "300",
                "2026-10-14T13:00", "120", "2026-10-14T13:30", "100");

        RealisedSavingsCalculator.Outcome outcome = RealisedSavingsCalculator.priceLinked(DRYER, actual, CHARGES, NOW);

        assertThat(outcome.state()).isEqualTo(RealisedSavingsCalculator.State.PENDING);
        assertThat(outcome.pendingReason()).isEqualTo(RealisedSavingsCalculator.NO_PRICE_YET);
    }

    @Test
    void aRunStartingPartWayThroughAHalfHourIsSplitByTimeSpentInEach() {
        AcceptedRun run = new AcceptedRun(5L, 10L, "Tumble Dryer", sg("2026-10-14T19:00"), sg("2026-10-14T13:15"),
                new BigDecimal("2.0"), 60, BigDecimal.ONE);

        List<SpendCalculator.HalfHour> halfHours = RealisedSavingsCalculator.halfHours(run.chosenStart(), run, Map.of());

        assertThat(halfHours).extracting(SpendCalculator.HalfHour::start)
                .containsExactly(sg("2026-10-14T13:00"), sg("2026-10-14T13:30"), sg("2026-10-14T14:00"));
        assertThat(halfHours).extracting(SpendCalculator.HalfHour::kwh)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("0.5"), new BigDecimal("1.0"), new BigDecimal("0.5"));
    }

    @Test
    void aFixedRatePlanSavesNothingByMovingTheRun() {
        RealisedSavingsCalculator.Outcome outcome =
                RealisedSavingsCalculator.fixedRate(DRYER, new BigDecimal("29.5"), NOW);

        // 2 kWh x 29.5 cents = $0.59 at either time.
        assertThat(outcome.usualCostSgd()).isEqualByComparingTo("0.59");
        assertThat(outcome.chosenCostSgd()).isEqualByComparingTo("0.59");
        assertThat(outcome.savingSgd()).isEqualByComparingTo("0.00");
    }

    private static Map<Instant, BigDecimal> prices(String... periodAndUsep) {
        Map<Instant, BigDecimal> prices = new HashMap<>();
        for (int i = 0; i < periodAndUsep.length; i += 2) {
            prices.put(sg(periodAndUsep[i]), new BigDecimal(periodAndUsep[i + 1]));
        }
        return prices;
    }

    static TariffComponent charge(String code, Unit unit, String rate, String from, String to) {
        return new TariffComponent(code, code, unit, new BigDecimal(rate), from == null ? null : LocalTime.parse(from),
                to == null ? null : LocalTime.parse(to), LocalDate.of(2026, 7, 1), null, "SP Group", null);
    }

    static Instant sg(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(SINGAPORE).toInstant();
    }
}

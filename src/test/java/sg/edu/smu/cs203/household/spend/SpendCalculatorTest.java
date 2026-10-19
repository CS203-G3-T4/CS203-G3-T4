package sg.edu.smu.cs203.household.spend;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import sg.edu.smu.cs203.household.spend.SpendCalculator.HalfHour;
import sg.edu.smu.cs203.household.spend.SpendCalculator.Line;
import sg.edu.smu.cs203.household.spend.SpendCalculator.Result;
import sg.edu.smu.cs203.household.spend.TariffComponent.Unit;

/**
 * CSDT4-41: the spend calculator against a small example worked out by hand.
 *
 * 1 Oct 2026, Singapore time (October has 31 x 48 = 1,488 half-hours):
 *   06:30 off-peak  0.5 kWh at USEP 200 SGD/MWh
 *   07:00 peak      1.0 kWh at USEP 300 SGD/MWh
 *   07:30 peak      0.4 kWh, no saved price -> skipped
 *
 *   energy        0.5 x 200/1000 + 1.0 x 300/1000          = 0.4000000
 *   transmission  0.5 x 0.0478 (off-peak) + 1.0 x 0.061 (peak) = 0.0239 + 0.0610
 *   MDSC          1.5 x 0.0019                             = 0.0028500
 *   settlement    1.5 x 0.001471                           = 0.0022065
 *   meter         2.18 x 3/1488 (share of the month passed) = 0.0043952
 *   subtotal                                               = 0.4943517
 *   GST 9%                                                 = 0.0444916
 *   total                                                  = 0.5388433  -> $0.54
 */
class SpendCalculatorTest {

    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
    private static final LocalDate FROM = LocalDate.of(2026, 7, 1);

    private final List<TariffComponent> charges = List.of(
            component("TRANSMISSION_PEAK", Unit.SGD_PER_KWH, "0.061000", "07:00", "23:00"),
            component("TRANSMISSION_OFF_PEAK", Unit.SGD_PER_KWH, "0.047800", "23:00", "07:00"),
            component("MARKET_DEVELOPMENT_SYSTEMS", Unit.SGD_PER_KWH, "0.001900", null, null),
            component("RETAIL_SETTLEMENT_UPLIFT", Unit.SGD_PER_KWH, "0.001471", null, null),
            component("METER_READING", Unit.SGD_PER_MONTH, "2.180000", null, null),
            component("GST", Unit.PERCENT, "9.000000", null, null));

    private final List<HalfHour> halfHours = List.of(
            new HalfHour(sg("2026-10-01T06:30"), new BigDecimal("0.5"), new BigDecimal("200")),
            new HalfHour(sg("2026-10-01T07:00"), new BigDecimal("1.0"), new BigDecimal("300")),
            new HalfHour(sg("2026-10-01T07:30"), new BigDecimal("0.4"), null));

    @Test
    void aPriceLinkedPlanMatchesTheHandWorkedExample() {
        Result result = SpendCalculator.priceLinked(halfHours, charges, 31 * 48);

        assertThat(result.energySgd()).isEqualByComparingTo("0.40");
        assertThat(amount(result, "TRANSMISSION_OFF_PEAK")).isEqualByComparingTo("0.0239");
        assertThat(amount(result, "TRANSMISSION_PEAK")).isEqualByComparingTo("0.0610");
        assertThat(amount(result, "MARKET_DEVELOPMENT_SYSTEMS")).isEqualByComparingTo("0.00285");
        assertThat(amount(result, "RETAIL_SETTLEMENT_UPLIFT")).isEqualByComparingTo("0.0022065");
        assertThat(amount(result, "METER_READING").setScale(7, RoundingMode.HALF_UP)).isEqualByComparingTo("0.0043952");
        assertThat(result.subtotalSgd().setScale(7, RoundingMode.HALF_UP)).isEqualByComparingTo("0.4943517");
        assertThat(result.gstSgd().setScale(7, RoundingMode.HALF_UP)).isEqualByComparingTo("0.0444916");
        assertThat(result.totalSgd().setScale(7, RoundingMode.HALF_UP)).isEqualByComparingTo("0.5388433");
        assertThat(SpendCalculator.sgd(result.totalSgd())).isEqualByComparingTo("0.54");
    }

    @Test
    void itIsUseTimesMarketPricePlusChargesTimes109() {
        // The story's formula: load x (USEP + non-energy charges) x 1.09, plus the meter share.
        BigDecimal perKwhOffPeak = new BigDecimal("0.2").add(new BigDecimal("0.0478")).add(new BigDecimal("0.0019"))
                .add(new BigDecimal("0.001471"));
        BigDecimal perKwhPeak = new BigDecimal("0.3").add(new BigDecimal("0.061")).add(new BigDecimal("0.0019"))
                .add(new BigDecimal("0.001471"));
        BigDecimal meter = new BigDecimal("2.18").multiply(BigDecimal.valueOf(3))
                .divide(BigDecimal.valueOf(1488), 12, RoundingMode.HALF_UP);
        BigDecimal byFormula = new BigDecimal("0.5").multiply(perKwhOffPeak)
                .add(BigDecimal.ONE.multiply(perKwhPeak)).add(meter).multiply(new BigDecimal("1.09"));

        Result result = SpendCalculator.priceLinked(halfHours, charges, 31 * 48);

        assertThat(result.totalSgd().setScale(9, RoundingMode.HALF_UP))
                .isEqualByComparingTo(byFormula.setScale(9, RoundingMode.HALF_UP));
    }

    @Test
    void aHalfHourWithoutAPriceIsSkippedAndCounted() {
        Result result = SpendCalculator.priceLinked(halfHours, charges, 31 * 48);

        assertThat(result.halfHoursSoFar()).isEqualTo(3);
        assertThat(result.halfHoursCounted()).isEqualTo(2);
        assertThat(result.halfHoursWithoutPrice()).isEqualTo(1);
        assertThat(result.kwhCounted()).isEqualByComparingTo("1.5");
        assertThat(result.kwhWithoutPrice()).isEqualByComparingTo("0.4");
    }

    @Test
    void eachChargeKeepsItsSourceAndGstComesLast() {
        Result result = SpendCalculator.priceLinked(halfHours, charges, 31 * 48);

        assertThat(result.lines()).extracting(Line::code).containsExactly("TRANSMISSION_PEAK",
                "TRANSMISSION_OFF_PEAK", "MARKET_DEVELOPMENT_SYSTEMS", "RETAIL_SETTLEMENT_UPLIFT", "METER_READING",
                "GST");
        assertThat(result.lines()).allSatisfy(line -> assertThat(line.source()).startsWith("Source for "));
    }

    @Test
    void aFixedRatePlanIsUseTimesTheContractedRate() {
        // 1.9 kWh x 29.5 cents = $0.5605, with or without a market price.
        Result result = SpendCalculator.fixedRate(halfHours, new BigDecimal("29.5"));

        assertThat(result.totalSgd()).isEqualByComparingTo("0.5605");
        assertThat(result.lines()).isEmpty();
        assertThat(result.halfHoursWithoutPrice()).isZero();
    }

    @Test
    void aRateChangeMidMonthUsesTheVersionInForceEachDay() {
        List<TariffComponent> versions = List.of(
                component("MARKET_DEVELOPMENT_SYSTEMS", Unit.SGD_PER_KWH, "0.0010", null, null),
                new TariffComponent("MARKET_DEVELOPMENT_SYSTEMS", "MDSC", Unit.SGD_PER_KWH, new BigDecimal("0.0030"),
                        null, null, LocalDate.of(2026, 10, 2), null, "Source for new", null));
        List<HalfHour> twoDays = List.of(
                new HalfHour(sg("2026-10-01T12:00"), BigDecimal.ONE, BigDecimal.ZERO),
                new HalfHour(sg("2026-10-02T12:00"), BigDecimal.ONE, BigDecimal.ZERO));

        Result result = SpendCalculator.priceLinked(twoDays, versions, 31 * 48);

        assertThat(result.totalSgd()).isEqualByComparingTo("0.0040");
    }

    @Test
    void offPeakHoursCrossMidnight() {
        TariffComponent offPeak = component("TRANSMISSION_OFF_PEAK", Unit.SGD_PER_KWH, "0.0478", "23:00", "07:00");

        assertThat(offPeak.appliesAt(LocalTime.of(23, 0))).isTrue();
        assertThat(offPeak.appliesAt(LocalTime.of(3, 30))).isTrue();
        assertThat(offPeak.appliesAt(LocalTime.of(6, 30))).isTrue();
        assertThat(offPeak.appliesAt(LocalTime.of(7, 0))).isFalse();
        assertThat(offPeak.appliesAt(LocalTime.of(22, 30))).isFalse();
    }

    private static BigDecimal amount(Result result, String code) {
        return result.lines().stream().filter(l -> l.code().equals(code)).findFirst().orElseThrow().amountSgd();
    }

    private static TariffComponent component(String code, Unit unit, String rate, String from, String to) {
        return new TariffComponent(code, code, unit, new BigDecimal(rate), from == null ? null : LocalTime.parse(from),
                to == null ? null : LocalTime.parse(to), FROM, null, "Source for " + code, "https://example.test");
    }

    private static Instant sg(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(SINGAPORE).toInstant();
    }
}

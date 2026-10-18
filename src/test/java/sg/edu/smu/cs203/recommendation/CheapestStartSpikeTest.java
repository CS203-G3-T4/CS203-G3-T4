package sg.edu.smu.cs203.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import sg.edu.smu.cs203.recommendation.CheapestStartCalculator.Outcome;
import sg.edu.smu.cs203.recommendation.CheapestStartCalculator.Result;
import sg.edu.smu.cs203.recommendation.CheapestStartCalculator.ShiftableRun;

/** CSDT4-38: a forecast spike during the usual run turns the suggestion into an AVOID warning. */
class CheapestStartSpikeTest {

    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
    private static final BigDecimal MINIMUM = new BigDecimal("0.05");
    private final Instant now = sg("2026-09-30T08:10");

    /** Tumble dryer: 2.5 kW for 60 minutes, 07:00-21:00, usually started at 19:45. */
    private final ShiftableRun dryer = new ShiftableRun(new BigDecimal("2.5"), 60,
            LocalTime.of(7, 0), LocalTime.of(21, 0), LocalTime.of(19, 45));

    @Test
    void aSpikeDuringTheUsualRunGivesAWarningWithTheCostAvoided() {
        // 19:45-20:45 touches 19:30, 20:00 and 20:30; 20:00 is a 3000 SGD/MWh spike.
        Result result = CheapestStartCalculator.cheapestStart(dryer,
                forecast(Map.of("20:00", 3000.0), Set.of("20:00")), now, MINIMUM);

        assertThat(result.outcome()).isEqualTo(Outcome.AVOID);
        assertThat(result.usualStart()).isEqualTo(sg("2026-09-30T19:45"));
        assertThat(result.spikeStart()).isEqualTo(sg("2026-09-30T20:00"));
        // 2.5 kW x (0.25 h x 300 + 0.5 h x 3000 + 0.25 h x 300) / 1000 = $4.125 at the usual time,
        // $0.75 at any spike-free start; a tie keeps the earliest (08:30).
        assertThat(result.usualCost()).isEqualByComparingTo("4.125");
        assertThat(result.bestStart()).isEqualTo(sg("2026-09-30T08:30"));
        assertThat(result.bestCost()).isEqualByComparingTo("0.75");
        assertThat(result.saving()).isEqualByComparingTo("3.375");
    }

    @Test
    void theAlternativeNeverTouchesASpike() {
        // 13:00 is cheap but flagged; 15:00 is cheap and not. Runs touching 13:00 are skipped.
        Result result = CheapestStartCalculator.cheapestStart(dryer,
                forecast(Map.of("13:00", 10.0, "15:00", 50.0, "15:30", 50.0, "20:00", 3000.0),
                        Set.of("13:00", "20:00")), now, MINIMUM);

        assertThat(result.outcome()).isEqualTo(Outcome.AVOID);
        assertThat(result.bestStart()).isEqualTo(sg("2026-09-30T15:00"));
    }

    @Test
    void aSpikeOutsideTheUsualRunIsNotAWarning() {
        Result result = CheapestStartCalculator.cheapestStart(dryer,
                forecast(Map.of("10:00", 3000.0, "13:00", 20.0, "13:30", 20.0), Set.of("10:00")), now, MINIMUM);

        assertThat(result.outcome()).isEqualTo(Outcome.SHIFT);
        assertThat(result.spikeStart()).isNull();
        assertThat(result.bestStart()).isEqualTo(sg("2026-09-30T13:00"));
    }

    @Test
    void withNoSpikeFreeStartInTheWindowThereIsNoWarning() {
        Set<String> everyHalfHour = new java.util.HashSet<>();
        for (int i = 0; i < 48; i++) {
            everyHalfHour.add(sg("2026-09-30T08:30").plusSeconds(1800L * i).atZone(SINGAPORE).toLocalTime().toString());
        }
        Result result = CheapestStartCalculator.cheapestStart(dryer, forecast(Map.of(), everyHalfHour), now, MINIMUM);

        assertThat(result.outcome()).isEqualTo(Outcome.USUAL_IS_CHEAPEST);
    }

    @Test
    void aSpikeThatCostsLessThanTheMinimumIsNotAWarning() {
        // Flagged, but only 1 SGD/MWh above the rest: avoiding it saves well under $0.05.
        Result result = CheapestStartCalculator.cheapestStart(dryer,
                forecast(Map.of("20:00", 301.0), Set.of("20:00")), now, MINIMUM);

        assertThat(result.outcome()).isEqualTo(Outcome.USUAL_IS_CHEAPEST);
    }

    @Test
    void aWarningForAWindowThatCrossesMidnightStaysInsideTheWindow() {
        // EV charger 22:00 -> 07:00 for 3 hours, usually from 22:00; spike at 23:00.
        ShiftableRun ev = new ShiftableRun(new BigDecimal("7.2"), 180,
                LocalTime.of(22, 0), LocalTime.of(7, 0), LocalTime.of(22, 0));

        Result result = CheapestStartCalculator.cheapestStart(ev,
                forecast(Map.of("23:00", 3000.0), Set.of("23:00")), now, MINIMUM);

        assertThat(result.outcome()).isEqualTo(Outcome.AVOID);
        assertThat(result.spikeStart()).isEqualTo(sg("2026-09-30T23:00"));
        assertThat(result.bestStart()).isEqualTo(sg("2026-09-30T23:30"));
        assertThat(result.bestStart().plus(Duration.ofMinutes(180))).isBeforeOrEqualTo(sg("2026-10-01T07:00"));
    }

    /** 48 half-hours from 08:30 at 300 SGD/MWh, with price overrides and spike flags by time. */
    private static List<PriceForecast.HalfHourPrice> forecast(Map<String, Double> overrides, Set<String> spikes) {
        List<PriceForecast.HalfHourPrice> points = new ArrayList<>();
        for (PriceForecast.HalfHourPrice point : CheapestStartCalculatorTest.forecast(
                sg("2026-09-30T08:30"), 48, 300, overrides)) {
            String time = point.periodStart().atZone(SINGAPORE).toLocalTime().toString();
            points.add(new PriceForecast.HalfHourPrice(point.periodStart(), point.usepSgdPerMwh(),
                    spikes.contains(time)));
        }
        return points;
    }

    private static Instant sg(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(SINGAPORE).toInstant();
    }
}

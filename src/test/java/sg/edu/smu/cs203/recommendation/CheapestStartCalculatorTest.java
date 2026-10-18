package sg.edu.smu.cs203.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import sg.edu.smu.cs203.recommendation.CheapestStartCalculator.Outcome;
import sg.edu.smu.cs203.recommendation.CheapestStartCalculator.Result;
import sg.edu.smu.cs203.recommendation.CheapestStartCalculator.ShiftableRun;

class CheapestStartCalculatorTest {

    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
    private static final BigDecimal ONE_CENT = new BigDecimal("0.01");

    // The mockup's dryer: 2.5 kW for 60 minutes, 07:00 -> must finish by 21:00, usually 19:45.
    private static final ShiftableRun DRYER = new ShiftableRun(new BigDecimal("2.5"), 60,
            LocalTime.of(7, 0), LocalTime.of(21, 0), LocalTime.of(19, 45));

    @Test
    void aDryerThatMustFinishBy2100IsNeverToldToStartAt2130() {
        // 21:00, 21:30 and 22:00 are by far the cheapest half-hours, but a 60-minute run starting
        // then would finish after 21:00. The latest legal start is 20:00.
        List<PriceForecast.HalfHourPrice> forecast = forecast(sg("2026-09-30T08:30"), 48, 300,
                Map.of("20:00", 50.0, "20:30", 50.0, "21:00", 1.0, "21:30", 1.0, "22:00", 1.0));

        Result result = CheapestStartCalculator.cheapestStart(DRYER, forecast, sg("2026-09-30T08:10"), ONE_CENT);

        assertThat(result.outcome()).isEqualTo(Outcome.SHIFT);
        assertThat(result.bestStart()).isEqualTo(sg("2026-09-30T20:00"));
        assertThat(result.bestStart()).isNotEqualTo(sg("2026-09-30T21:30"));
    }

    @Test
    void costsFollowTheFormulaIncludingPartHalfHours() {
        List<PriceForecast.HalfHourPrice> forecast = forecast(sg("2026-09-30T08:30"), 48, 300,
                Map.of("20:00", 50.0, "20:30", 50.0));

        Result result = CheapestStartCalculator.cheapestStart(DRYER, forecast, sg("2026-09-30T08:10"), ONE_CENT);

        // Usual 19:45-20:45: 15 min at 300 + 30 min at 50 + 15 min at 50, x 2.5 kW / 1000
        assertThat(result.usualStart()).isEqualTo(sg("2026-09-30T19:45"));
        assertThat(result.usualCost()).isEqualByComparingTo("0.2813");
        // Best 20:00-21:00: 60 min at 50 x 2.5 kW / 1000 = 0.125
        assertThat(result.bestCost()).isEqualByComparingTo("0.1250");
        assertThat(result.saving()).isEqualByComparingTo("0.1563");
    }

    @Test
    void aFlatForecastGivesNoSuggestion() {
        Result result = CheapestStartCalculator.cheapestStart(DRYER,
                forecast(sg("2026-09-30T08:30"), 48, 200, Map.of()), sg("2026-09-30T08:10"), ONE_CENT);

        assertThat(result.outcome()).isEqualTo(Outcome.USUAL_IS_CHEAPEST);
    }

    @Test
    void savingsBelowTheMinimumAreNotSuggested() {
        List<PriceForecast.HalfHourPrice> forecast = forecast(sg("2026-09-30T08:30"), 48, 200,
                Map.of("10:00", 199.0, "10:30", 199.0));

        Result result = CheapestStartCalculator.cheapestStart(DRYER, forecast, sg("2026-09-30T08:10"), ONE_CENT);

        // 2.5 kW x 1 h x 1 SGD/MWh cheaper = $0.0025, under one cent
        assertThat(result.outcome()).isEqualTo(Outcome.USUAL_IS_CHEAPEST);
    }

    @Test
    void aTieKeepsTheEarlierStart() {
        List<PriceForecast.HalfHourPrice> forecast = forecast(sg("2026-09-30T08:30"), 48, 300,
                Map.of("10:00", 10.0, "10:30", 10.0, "14:00", 10.0, "14:30", 10.0));

        Result result = CheapestStartCalculator.cheapestStart(DRYER, forecast, sg("2026-09-30T08:10"), ONE_CENT);

        assertThat(result.bestStart()).isEqualTo(sg("2026-09-30T10:00"));
    }

    @Test
    void neverSuggestsAStartBeforeNow() {
        List<PriceForecast.HalfHourPrice> forecast = forecast(sg("2026-09-30T08:30"), 48, 300,
                Map.of("08:30", 1.0, "09:00", 1.0));

        Result result = CheapestStartCalculator.cheapestStart(DRYER, forecast, sg("2026-09-30T08:40"), ONE_CENT);

        assertThat(result.bestStart()).isAfterOrEqualTo(sg("2026-09-30T08:40"));
    }

    @Test
    void aWindowThatCrossesMidnightMustStillFinishByItsEnd() {
        ShiftableRun ev = new ShiftableRun(new BigDecimal("7.2"), 180,
                LocalTime.of(22, 0), LocalTime.of(7, 0), LocalTime.of(22, 0));
        List<PriceForecast.HalfHourPrice> forecast = forecast(sg("2026-09-30T12:30"), 48, 400,
                Map.of("04:00", 100.0, "04:30", 100.0, "05:00", 90.0, "05:30", 90.0, "06:00", 90.0,
                        "06:30", 5.0, "07:00", 1.0, "07:30", 1.0));

        Result result = CheapestStartCalculator.cheapestStart(ev, forecast, sg("2026-09-30T12:10"), ONE_CENT);

        // 07:00 and 07:30 are cheapest but outside the window; 04:00 -> 07:00 is the latest legal run.
        assertThat(result.bestStart()).isEqualTo(sg("2026-10-01T04:00"));
        assertThat(result.usualStart()).isEqualTo(sg("2026-09-30T22:00"));
    }

    @Test
    void ifTheForecastDoesNotCoverTheNextUsualRunNothingIsCompared() {
        // F3 only forecasts 12 hours ahead. At 20:10 today's 19:45 run has passed and
        // tomorrow's is beyond the forecast.
        Result result = CheapestStartCalculator.cheapestStart(DRYER,
                forecast(sg("2026-09-30T20:30"), 24, 200, Map.of()), sg("2026-09-30T20:10"), ONE_CENT);

        assertThat(result.outcome()).isEqualTo(Outcome.NOT_ENOUGH_FORECAST);
    }

    @Test
    void aUsualRunThatHasAlreadyStartedIsNotCompared() {
        // A forecast made at 19:29 starts at 19:30 and can still be fresh at 20:05, but today's
        // 19:45 run has already begun, so the next run is tomorrow's (beyond this forecast).
        Result result = CheapestStartCalculator.cheapestStart(DRYER,
                forecast(sg("2026-09-30T19:30"), 24, 200, Map.of()), sg("2026-09-30T20:05"), ONE_CENT);

        assertThat(result.outcome()).isEqualTo(Outcome.NOT_ENOUGH_FORECAST);
        assertThat(result.usualStart()).isEqualTo(sg("2026-10-01T19:45"));
    }

    @Test
    void withoutAUsualStartTheWindowStartIsUsed() {
        ShiftableRun noUsual = new ShiftableRun(new BigDecimal("2.5"), 60,
                LocalTime.of(7, 0), LocalTime.of(21, 0), null);

        Result result = CheapestStartCalculator.cheapestStart(noUsual,
                forecast(sg("2026-09-30T06:30"), 48, 300, Map.of("13:00", 10.0, "13:30", 10.0)),
                sg("2026-09-30T06:10"), ONE_CENT);

        assertThat(result.usualStart()).isEqualTo(sg("2026-09-30T07:00"));
        assertThat(result.bestStart()).isEqualTo(sg("2026-09-30T13:00"));
    }

    @Test
    void anOverrideResolvesInsideTheSuggestionsWindowOrIsRejected() {
        Instant suggested = sg("2026-09-30T14:00");

        assertThat(CheapestStartCalculator.startInSameWindow(DRYER, suggested, LocalTime.of(15, 10)))
                .isEqualTo(sg("2026-09-30T15:10"));
        assertThat(CheapestStartCalculator.startInSameWindow(DRYER, suggested, LocalTime.of(20, 30)))
                .isNull();

        ShiftableRun ev = new ShiftableRun(new BigDecimal("7.2"), 180,
                LocalTime.of(22, 0), LocalTime.of(7, 0), LocalTime.of(22, 0));
        assertThat(CheapestStartCalculator.startInSameWindow(ev, sg("2026-10-01T02:00"), LocalTime.of(23, 0)))
                .isEqualTo(sg("2026-09-30T23:00"));
    }

    private static Instant sg(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(SINGAPORE).toInstant();
    }

    /** n half-hours from `from` at `price`, with some times of day (HH:mm) priced differently. */
    static List<PriceForecast.HalfHourPrice> forecast(Instant from, int n, double price,
                                                      Map<String, Double> overrides) {
        List<PriceForecast.HalfHourPrice> points = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Instant start = from.plusSeconds(1800L * i);
            String time = start.atZone(SINGAPORE).toLocalTime().toString();
            points.add(new PriceForecast.HalfHourPrice(start,
                    BigDecimal.valueOf(overrides.getOrDefault(time, price))));
        }
        return points;
    }
}

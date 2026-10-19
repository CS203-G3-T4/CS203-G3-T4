package sg.edu.smu.cs203.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import sg.edu.smu.cs203.recommendation.CheapestStartCalculator.Outcome;
import sg.edu.smu.cs203.recommendation.CheapestStartCalculator.Result;
import sg.edu.smu.cs203.recommendation.CheapestStartCalculator.ShiftableRun;

/** CSDT4-39: explanations use the real forecast prices (¢/kWh), times and window limit. */
class RecommendationExplanationTest {

    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
    private static final PriceForecast DEMO =
            new PriceForecast(PriceForecast.DEMO, "Replay day", Instant.EPOCH, List.of());
    private static final PriceForecast F3 = new PriceForecast(PriceForecast.F3, "B2", Instant.EPOCH, List.of());

    /** 1 kW for an hour = 1 kWh, so a run's cost in dollars x 100 is its average ¢/kWh. */
    private final ShiftableRun washer = new ShiftableRun(BigDecimal.ONE, 60,
            LocalTime.of(18, 0), LocalTime.of(23, 30), LocalTime.of(19, 0));

    @Test
    void aLaterStartSaysPricesFallAndThatItStillFinishesInTime() {
        Result result = new Result(Outcome.SHIFT, sg("2026-09-30T19:00"), new BigDecimal("0.31"),
                sg("2026-09-30T22:00"), new BigDecimal("0.20"), new BigDecimal("0.11"));

        assertThat(RecommendationExplanation.explain(result, washer, DEMO)).isEqualTo(
                "Prices are forecast to fall from 31¢ to 20¢/kWh by 10:00 PM. Starting at 10:00 PM still "
                        + "finishes by 11:00 PM, before your 11:30 PM limit. Estimated saving: $0.11 on this run "
                        + "($0.20 vs $0.31). Based on a demo forecast (a replay of past prices), not a live "
                        + "prediction.");
    }

    @Test
    void anEarlierStartComparesBothPricesAndNamesF3sModel() {
        Result result = new Result(Outcome.SHIFT, sg("2026-09-30T19:00"), new BigDecimal("0.31"),
                sg("2026-09-30T18:00"), new BigDecimal("0.095"), new BigDecimal("0.215"));

        assertThat(RecommendationExplanation.explain(result, washer, F3)).isEqualTo(
                "Prices are forecast to be lower earlier: 9.5¢/kWh at 6:00 PM against 31¢/kWh at your usual "
                        + "7:00 PM. Starting at 6:00 PM still finishes by 7:00 PM, before your 11:30 PM limit. "
                        + "Estimated saving: $0.22 on this run ($0.10 vs $0.31). Based on F3's forecast (B2).");
    }

    @Test
    void aSpikeWarningGivesTheSpikeTimeAndBothAveragePrices() {
        Result result = new Result(Outcome.AVOID, sg("2026-09-30T19:00"), new BigDecimal("2.40"),
                sg("2026-09-30T22:00"), new BigDecimal("0.20"), new BigDecimal("2.20"), sg("2026-09-30T19:30"));

        assertThat(RecommendationExplanation.explain(result, washer, DEMO)).startsWith(
                "A price spike is forecast from 7:30 PM: your usual 7:00 PM run would average 240¢/kWh. "
                        + "Starting at 10:00 PM instead averages 20¢/kWh and still finishes by 11:00 PM, before "
                        + "your 11:30 PM limit. Estimated saving: $2.20 on this run ($0.20 vs $2.40).");
    }

    @Test
    void aRunThatEndsRightOnTheLimitSaysSoIncludingAcrossMidnight() {
        ShiftableRun ev = new ShiftableRun(new BigDecimal("7.2"), 180, LocalTime.of(22, 0), LocalTime.of(7, 0),
                LocalTime.of(22, 0));

        assertThat(RecommendationExplanation.finishes(sg("2026-10-01T04:00"), ev))
                .isEqualTo("still finishes at 7:00 AM, your limit.");
    }

    @Test
    void aMidnightLimitIsCalledMidnight() {
        ShiftableRun heater = new ShiftableRun(BigDecimal.ONE, 30, LocalTime.of(6, 0), LocalTime.MIDNIGHT,
                LocalTime.of(7, 0));

        assertThat(RecommendationExplanation.finishes(sg("2026-09-30T14:30"), heater))
                .isEqualTo("still finishes by 3:00 PM, before your midnight limit.");
        assertThat(RecommendationExplanation.finishes(sg("2026-09-30T23:30"), heater))
                .isEqualTo("still finishes at midnight, your limit.");
    }

    @Test
    void pricesUnderTenCentsKeepOneDecimal() {
        assertThat(RecommendationExplanation.centsPerKwh(new BigDecimal("0.0200"), washer)).isEqualTo("2¢");
        assertThat(RecommendationExplanation.centsPerKwh(new BigDecimal("0.0954"), washer)).isEqualTo("9.5¢");
        assertThat(RecommendationExplanation.centsPerKwh(new BigDecimal("3.1049"), washer)).isEqualTo("310¢");
    }

    private static Instant sg(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(SINGAPORE).toInstant();
    }
}

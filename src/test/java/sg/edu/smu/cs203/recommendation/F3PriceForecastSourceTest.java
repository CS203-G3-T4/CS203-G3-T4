package sg.edu.smu.cs203.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import sg.edu.smu.cs203.forecast.ForecastService;
import sg.edu.smu.cs203.forecast.ForecastTypes;

class F3PriceForecastSourceTest {

    private final ForecastService forecasts = mock(ForecastService.class);
    private final F3PriceForecastSource source = new F3PriceForecastSource(forecasts);
    private final Instant asOf = Instant.parse("2026-09-30T00:10:00Z");

    @Test
    void aForecastF3DoesNotMarkActionableIsNotUsed() {
        when(forecasts.latest()).thenReturn(new ForecastTypes.View(true, false, false, "LIVE",
                "PERIOD_MAPPING_UNVERIFIED", run()));

        PriceForecastSource.Lookup lookup = source.forecast();

        assertThat(lookup.available()).isFalse();
        assertThat(lookup.unavailableReason()).contains("PERIOD_MAPPING_UNVERIFIED");
    }

    @Test
    void anActionableForecastBecomesHalfHourPrices() {
        when(forecasts.latest()).thenReturn(new ForecastTypes.View(true, true, false, "LIVE", null, run()));

        PriceForecastSource.Lookup lookup = source.forecast();

        assertThat(lookup.available()).isTrue();
        assertThat(lookup.forecast().source()).isEqualTo(PriceForecast.F3);
        assertThat(lookup.forecast().model()).isEqualTo("B2");
        assertThat(lookup.forecast().points()).extracting(PriceForecast.HalfHourPrice::usepSgdPerMwh)
                .containsExactly(new BigDecimal("180.5"), new BigDecimal("420.0"));
    }

    @Test
    void f3SpikeFlagsAreKeptAndAnUnknownFlagIsNotASpike() {
        List<ForecastTypes.Point> points = List.of(
                new ForecastTypes.Point(1, Instant.parse("2026-09-30T00:30:00Z"), new BigDecimal("180.5"),
                        new BigDecimal("400"), false),
                new ForecastTypes.Point(2, Instant.parse("2026-09-30T01:00:00Z"), new BigDecimal("2500"),
                        new BigDecimal("400"), true),
                new ForecastTypes.Point(3, Instant.parse("2026-09-30T01:30:00Z"), new BigDecimal("2500"), null, null));
        when(forecasts.latest()).thenReturn(new ForecastTypes.View(true, true, false, "LIVE", null, run(points)));

        assertThat(source.forecast().forecast().points()).extracting(PriceForecast.HalfHourPrice::spike)
                .containsExactly(false, true, false);
    }

    private ForecastTypes.Run run() {
        return run(List.of(
                new ForecastTypes.Point(1, Instant.parse("2026-09-30T00:30:00Z"), new BigDecimal("180.5"), null, null),
                new ForecastTypes.Point(2, Instant.parse("2026-09-30T01:00:00Z"), new BigDecimal("420.0"),
                        null, null)));
    }

    private ForecastTypes.Run run(List<ForecastTypes.Point> points) {
        return new ForecastTypes.Run("run-1", asOf, asOf, "input", "LIVE", "BASELINE", "B2-v1", "B2",
                null, List.of(), false, "AVAILABLE", points);
    }
}

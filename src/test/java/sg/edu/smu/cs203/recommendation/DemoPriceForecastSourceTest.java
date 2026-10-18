package sg.edu.smu.cs203.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;

import org.junit.jupiter.api.Test;

class DemoPriceForecastSourceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-30T00:10:00Z"), ZoneId.of("Asia/Singapore"));

    @Test
    void theBundledReplayDayLoadsDespiteItsByteOrderMark() {
        DemoPriceForecastSource source = DemoPriceForecastSource.fromClasspath(
                "data/replay/2026-09-26_data(main_day).csv", clock);

        PriceForecastSource.Lookup lookup = source.forecast();

        assertThat(lookup.available()).isTrue();
        assertThat(lookup.forecast().isDemo()).isTrue();
        assertThat(lookup.forecast().points()).hasSize(48);
        // 00:10 UTC is 08:10 in Singapore, so the first forecast half-hour is 08:30 SGT.
        assertThat(lookup.forecast().points().getFirst().periodStart())
                .isEqualTo(Instant.parse("2026-09-30T00:30:00Z"));
    }

    @Test
    void pricesAreRepeatedByTimeOfDay() throws Exception {
        Map<LocalTime, BigDecimal> curve = DemoPriceForecastSource.parse(new StringReader(
                "\uFEFFfirst_collected_sgt,usep_sgd_per_mwh,demand_forecast_mw\n"
                        + "2026-09-26T16:30:00+08:00,4120.84,7234\n"));

        assertThat(curve).containsEntry(LocalTime.of(16, 30), new BigDecimal("4120.84"));
    }

    @Test
    void aCurveWithoutEveryHalfHourIsRejected() {
        assertThatThrownBy(() -> new DemoPriceForecastSource(
                Map.of(LocalTime.of(0, 0), BigDecimal.ONE), "x", clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("48");
    }
}

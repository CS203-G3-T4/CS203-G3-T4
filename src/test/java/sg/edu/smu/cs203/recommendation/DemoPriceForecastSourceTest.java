package sg.edu.smu.cs203.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
    void spikesAreMarkedByF3sRuleAgainstTheWeeksBeforeTheReplayDay() {
        List<Instant> asOfs = new ArrayList<>();
        SpikeThresholds thresholds = (targets, asOf) -> {
            asOfs.add(asOf);
            Map<Instant, BigDecimal> found = new HashMap<>();
            targets.forEach(target -> found.put(target, new BigDecimal("1000")));
            return found;
        };
        DemoPriceForecastSource source = DemoPriceForecastSource.fromClasspath(
                "data/replay/2026-09-26_data(main_day).csv", clock, thresholds);

        List<PriceForecast.HalfHourPrice> points = source.forecast().forecast().points();
        source.forecast();

        // Judged on history before 26 Sep 00:00 SGT, as if F3 had forecast that day; found once.
        assertThat(asOfs).containsExactly(sgt("2026-09-26T00:00"));
        assertThat(spikeAt(points, "16:30")).isTrue();   // 4120.84 on the replay day
        assertThat(spikeAt(points, "13:00")).isFalse();  // 178.94
    }

    @Test
    void withoutEnoughHistoryNothingIsMarkedAndItTriesAgainNextTime() {
        int[] calls = {0};
        SpikeThresholds none = (targets, asOf) -> {
            calls[0]++;
            return Map.of();
        };
        DemoPriceForecastSource source = DemoPriceForecastSource.fromClasspath(
                "data/replay/2026-09-26_data(main_day).csv", clock, none);

        assertThat(source.forecast().forecast().points()).noneMatch(PriceForecast.HalfHourPrice::spike);
        source.forecast();
        assertThat(calls[0]).isEqualTo(2);
    }

    @Test
    void withoutThresholdsTheDemoMarksNoSpikes() {
        DemoPriceForecastSource source = DemoPriceForecastSource.fromClasspath(
                "data/replay/2026-09-26_data(main_day).csv", clock);

        assertThat(source.forecast().forecast().points()).noneMatch(PriceForecast.HalfHourPrice::spike);
    }

    private static boolean spikeAt(List<PriceForecast.HalfHourPrice> points, String time) {
        LocalTime wanted = LocalTime.parse(time);
        return points.stream()
                .filter(p -> p.periodStart().atZone(ZoneId.of("Asia/Singapore")).toLocalTime().equals(wanted))
                .findFirst().orElseThrow().spike();
    }

    private static Instant sgt(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(ZoneId.of("Asia/Singapore")).toInstant();
    }

    @Test
    void aCurveWithoutEveryHalfHourIsRejected() {
        assertThatThrownBy(() -> new DemoPriceForecastSource(
                Map.of(LocalTime.of(0, 0), BigDecimal.ONE), "x", clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("48");
    }
}

package sg.edu.smu.cs203.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import sg.edu.smu.cs203.common.DemoClock;
import sg.edu.smu.cs203.forecast.ForecastService;
import sg.edu.smu.cs203.forecast.ForecastTypes;
import sg.edu.smu.cs203.market.price.ReplayPriceCatalog;

class PriceForecastConfigurationTest {
    private final Instant liveTime = Instant.parse("2026-11-01T00:10:00Z");
    private final DemoClock clock = new DemoClock(Clock.fixed(liveTime, ZoneOffset.UTC));
    private final ForecastService live = mock(ForecastService.class);

    @SuppressWarnings("unchecked")
    private PriceForecastSource configuredSource() {
        ObjectProvider<ForecastService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(live);
        return new PriceForecastConfiguration().priceForecastSource("f3", LocalDate.parse("2026-09-26"),
                false, provider, null, null,
                new ReplayPriceCatalog("data/replay/2026-09-26_data(main_day).csv"), clock);
    }

    @Test
    void startingAdvancingAndStoppingReplaySwitchesPricesWithoutReplacingTheLiveSource() {
        var run = new ForecastTypes.Run("live-1", liveTime, liveTime, "input", "LIVE", "AI", "price-only-v1", "AI",
                null, List.of("EXPERIMENTAL_AI"), false, "AVAILABLE",
                List.of(new ForecastTypes.Point(1, liveTime.plusSeconds(1200), new BigDecimal("150"), null, null)));
        when(live.latest()).thenReturn(new ForecastTypes.View(true, true, false, "LIVE", null, run));
        var source = configuredSource();
        assertThat(source.forecast().forecast().source()).isEqualTo(PriceForecast.F3);

        Instant start = Instant.parse("2026-09-26T04:00:00Z");
        clock.startAt(start);
        var replay = source.forecast().forecast();
        assertThat(replay.source()).isEqualTo(PriceForecast.DEMO);
        assertThat(replay.replayDay()).isEqualTo(LocalDate.parse("2026-09-26"));
        assertThat(replay.asOf()).isEqualTo(start);
        assertThat(replay.points()).hasSize(48);
        clock.advance(Duration.ofMinutes(30));
        assertThat(source.forecast().forecast().asOf()).isEqualTo(start.plusSeconds(1800));
        verify(live, times(1)).latest();

        clock.stop();
        assertThat(source.forecast().forecast().source()).isEqualTo(PriceForecast.F3);
        assertThat(source.forecast().forecast().modelVersion()).isEqualTo("price-only-v1");
    }

    @Test
    void liveOutageDoesNotDisableReplayAndDoesNotSilentlyBecomeDemoWhenReplayStops() {
        when(live.latest()).thenReturn(new ForecastTypes.View(false, false, true, "LIVE", "STALE_INPUT", null));
        var source = configuredSource();
        assertThat(source.forecast().available()).isFalse();
        clock.startAt(Instant.parse("2026-09-26T04:00:00Z"));
        assertThat(source.forecast().forecast().source()).isEqualTo(PriceForecast.DEMO);
        clock.stop();
        assertThat(source.forecast().available()).isFalse();
    }
}

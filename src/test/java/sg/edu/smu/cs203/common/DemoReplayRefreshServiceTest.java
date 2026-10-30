package sg.edu.smu.cs203.common;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import sg.edu.smu.cs203.forecast.ForecastJob;
import sg.edu.smu.cs203.market.price.ReplayPriceService;
import sg.edu.smu.cs203.recommendation.RecommendationService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Clock;
import java.time.ZoneId;

class DemoReplayRefreshServiceTest {

    @Test
    void refreshesForecastAndOnlyTheDedicatedReplayHousehold() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ReplayPriceService replayPrices = mock(ReplayPriceService.class);
        ForecastJob forecasts = mock(ForecastJob.class);
        RecommendationService recommendations = mock(RecommendationService.class);
        DemoClock clock = new DemoClock(Clock.fixed(Instant.EPOCH, ZoneId.of("Asia/Singapore")));
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq("Replay Demo Household")))
                .thenReturn(4242L);

        new DemoReplayRefreshService(jdbc, replayPrices, forecasts, recommendations, clock).refresh();

        verify(forecasts).runOnce();
        verify(recommendations).generate(4242L);
    }

    @Test
    void startResetsTheReplayHouseholdBeforeRefreshingIt() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ReplayPriceService replayPrices = mock(ReplayPriceService.class);
        ForecastJob forecasts = mock(ForecastJob.class);
        RecommendationService recommendations = mock(RecommendationService.class);
        DemoClock clock = new DemoClock(Clock.fixed(Instant.EPOCH, ZoneId.of("Asia/Singapore")));
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq("Replay Demo Household")))
                .thenReturn(4242L);

        Instant start = Instant.parse("2026-09-26T00:00:00Z");
        new DemoReplayRefreshService(jdbc, replayPrices, forecasts, recommendations, clock)
                .start(LocalDate.parse("2026-09-26"), start);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(recommendations, forecasts);
        verify(replayPrices).loadDay(LocalDate.parse("2026-09-26"));
        org.assertj.core.api.Assertions.assertThat(clock.snapshot())
                .isEqualTo(new DemoClock.Snapshot(true, start));
        order.verify(recommendations).resetForReplay(4242L);
        order.verify(forecasts).runOnce();
        order.verify(recommendations).generate(4242L);
    }
}

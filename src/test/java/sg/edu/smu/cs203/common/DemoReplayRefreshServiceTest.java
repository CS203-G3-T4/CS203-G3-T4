package sg.edu.smu.cs203.common;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import sg.edu.smu.cs203.forecast.ForecastJob;
import sg.edu.smu.cs203.recommendation.RecommendationService;

class DemoReplayRefreshServiceTest {

    @Test
    void refreshesForecastAndOnlyTheDedicatedReplayHousehold() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ForecastJob forecasts = mock(ForecastJob.class);
        RecommendationService recommendations = mock(RecommendationService.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq("Replay Demo Household")))
                .thenReturn(4242L);

        new DemoReplayRefreshService(jdbc, forecasts, recommendations).refresh();

        verify(forecasts).runOnce();
        verify(recommendations).generate(4242L);
    }

    @Test
    void startResetsTheReplayHouseholdBeforeRefreshingIt() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ForecastJob forecasts = mock(ForecastJob.class);
        RecommendationService recommendations = mock(RecommendationService.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq("Replay Demo Household")))
                .thenReturn(4242L);

        new DemoReplayRefreshService(jdbc, forecasts, recommendations).start();

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(recommendations, forecasts);
        order.verify(recommendations).resetForReplay(4242L);
        order.verify(forecasts).runOnce();
        order.verify(recommendations).generate(4242L);
    }
}

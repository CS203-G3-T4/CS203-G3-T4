package sg.edu.smu.cs203.market.price;

import org.junit.jupiter.api.Test;
import sg.edu.smu.cs203.market.weather.WeatherIngestionService;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class MarketIngestionSchedulerTest {
    @Test
    void pollsBothAtStartupAndOnSchedule() {
        var market = mock(MarketIngestionService.class);
        var weather = mock(WeatherIngestionService.class);
        var scheduler = new MarketIngestionScheduler(market, weather);
        scheduler.pollAtStartup();
        scheduler.pollOnSchedule();
        verify(market, times(2)).poll();
        verify(weather, times(2)).poll();
    }

    @Test
    void weatherStillRunsIfMarketPersistenceFails() {
        var market = mock(MarketIngestionService.class);
        var weather = mock(WeatherIngestionService.class);
        doThrow(new IllegalStateException("database unavailable")).when(market).poll();
        assertThatThrownBy(() -> new MarketIngestionScheduler(market, weather).pollOnSchedule())
                .isInstanceOf(IllegalStateException.class);
        verify(weather).poll();
    }
}

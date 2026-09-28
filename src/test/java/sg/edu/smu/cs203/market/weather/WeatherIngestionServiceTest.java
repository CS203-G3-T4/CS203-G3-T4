package sg.edu.smu.cs203.market.weather;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

class WeatherIngestionServiceTest {
    private final WeatherClient client = mock(WeatherClient.class);
    private final WeatherRepository repository = mock(WeatherRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-23T08:00:00Z"), ZoneOffset.UTC);

    @Test
    void persistsForecastItems() {
        var items = new ObjectMapper().readTree("[{\"timestamp\":\"2026-09-23T15:50:00+08:00\"}]");
        when(client.fetch()).thenReturn(items);
        new WeatherIngestionService(client, repository, clock).poll();
        verify(repository).save(items, clock.instant());
    }

    @Test
    void failedUpstreamDoesNotTouchStoredForecasts() {
        when(client.fetch()).thenThrow(new IllegalStateException("upstream unavailable"));
        assertThatCode(() -> new WeatherIngestionService(client, repository, clock).poll()).doesNotThrowAnyException();
        verifyNoInteractions(repository);
    }
}

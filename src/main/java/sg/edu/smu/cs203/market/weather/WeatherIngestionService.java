package sg.edu.smu.cs203.market.weather;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class WeatherIngestionService {
    private static final Logger LOG = LoggerFactory.getLogger(WeatherIngestionService.class);
    private final WeatherClient client;
    private final WeatherRepository repository;
    private final Clock clock;

    public WeatherIngestionService(WeatherClient client, WeatherRepository repository, Clock clock) {
        this.client = client;
        this.repository = repository;
        this.clock = clock;
    }

    public void poll() {
        try {
            repository.save(client.fetch(), clock.instant());
        } catch (RuntimeException exception) {
            LOG.error("NEA ingestion failed; stored forecasts remain available", exception);
        }
    }
}

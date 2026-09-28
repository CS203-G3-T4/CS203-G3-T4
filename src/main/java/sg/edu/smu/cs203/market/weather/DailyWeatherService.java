package sg.edu.smu.cs203.market.weather;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class DailyWeatherService {
    private static final Logger LOG = LoggerFactory.getLogger(DailyWeatherService.class);
    private final DailyWeatherClient client;
    private final DailyWeatherRepository repository;
    private final Clock clock;
    private final Duration staleAfter;
    private final BigDecimal heatThresholdC;
    private volatile boolean lastPollFailed;

    public DailyWeatherService(DailyWeatherClient client, DailyWeatherRepository repository, Clock clock,
            @Value("${weather.daily.stale-after}") Duration staleAfter,
            @Value("${weather.daily.heat-threshold-c}") BigDecimal heatThresholdC) {
        this.client = client;
        this.repository = repository;
        this.clock = clock;
        this.staleAfter = staleAfter;
        this.heatThresholdC = heatThresholdC;
    }

    public void poll() {
        try {
            repository.save(client.fetch(), clock.instant());
            lastPollFailed = false;
        } catch (RuntimeException exception) {
            lastPollFailed = true;
            LOG.error("NEA daily forecast ingestion failed; saved forecasts remain available", exception);
        }
    }

    public TodayResponse today() {
        Instant now = clock.instant();
        return repository.findByDate(LocalDate.now(clock)).map(forecast -> {
            boolean stale = lastPollFailed || now.isBefore(forecast.validStart())
                    || !now.isBefore(forecast.validEnd()) || forecast.updatedAt().isAfter(now.plusSeconds(120))
                    || Duration.between(forecast.fetchedAt(), now).compareTo(staleAfter) > 0;
            return new TodayResponse(true, forecast, stale,
                    !stale && forecast.temperatureHighC().compareTo(heatThresholdC) >= 0, heatThresholdC);
        }).orElseGet(() -> new TodayResponse(false, null, true, false, heatThresholdC));
    }

    public record TodayResponse(boolean available, DailyWeatherForecast forecast, boolean stale,
                                boolean heatHint, BigDecimal heatThresholdC) {}
}

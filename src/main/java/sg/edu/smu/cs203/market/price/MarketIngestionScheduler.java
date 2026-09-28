package sg.edu.smu.cs203.market.price;

import sg.edu.smu.cs203.market.weather.WeatherIngestionService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "market.feed.enabled", havingValue = "true", matchIfMissing = true)
public class MarketIngestionScheduler {

    private final MarketIngestionService ingestion;
    private final WeatherIngestionService weather;

    public MarketIngestionScheduler(MarketIngestionService ingestion, WeatherIngestionService weather) {
        this.ingestion = ingestion;
        this.weather = weather;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void pollAtStartup() {
        pollBoth();
    }

    private void pollBoth() {
        try {
            ingestion.poll();
        } finally {
            weather.poll();
        }
    }

    @Scheduled(cron = "${market.feed.cron}", zone = "Asia/Singapore")
    public void pollOnSchedule() {
        pollBoth();
    }
}

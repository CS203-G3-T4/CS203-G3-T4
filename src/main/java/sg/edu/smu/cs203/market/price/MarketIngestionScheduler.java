package sg.edu.smu.cs203.market.price;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "market.feed.enabled", havingValue = "true", matchIfMissing = true)
public class MarketIngestionScheduler {

    private final MarketIngestionService ingestion;

    public MarketIngestionScheduler(MarketIngestionService ingestion) {
        this.ingestion = ingestion;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void pollAtStartup() {
        ingestion.poll();
    }

    @Scheduled(cron = "${market.feed.cron}", zone = "Asia/Singapore")
    public void pollOnSchedule() {
        ingestion.poll();
    }
}

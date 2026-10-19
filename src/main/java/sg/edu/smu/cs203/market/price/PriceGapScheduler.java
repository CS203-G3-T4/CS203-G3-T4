package sg.edu.smu.cs203.market.price;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the missing half-hour check at startup and once a day (CSDT4-77). */
@Component
@ConditionalOnProperty(name = "market.feed.enabled", havingValue = "true", matchIfMissing = true)
public class PriceGapScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(PriceGapScheduler.class);

    private final PriceGapService gaps;

    public PriceGapScheduler(PriceGapService gaps) {
        this.gaps = gaps;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void checkAtStartup() {
        run();
    }

    @Scheduled(cron = "${market.gaps.cron}", zone = "Asia/Singapore")
    public void checkDaily() {
        run();
    }

    private void run() {
        try {
            gaps.check();
        } catch (RuntimeException exception) {
            LOG.error("Missing half-hour check failed", exception);
        }
    }
}

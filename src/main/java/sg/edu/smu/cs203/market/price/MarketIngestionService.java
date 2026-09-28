package sg.edu.smu.cs203.market.price;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;

@Service
public class MarketIngestionService {

    private static final Logger LOG = LoggerFactory.getLogger(MarketIngestionService.class);

    private final UsepClient client;
    private final PriceNormalizer normalizer;
    private final MarketPriceRepository prices;
    private final IngestionRunRepository runs;
    private final Clock clock;
    private final MarketObservationRepository observations;
    private final Duration staleAfter;
    private final ApplicationEventPublisher events;

    public MarketIngestionService(
            UsepClient client,
            PriceNormalizer normalizer,
            MarketPriceRepository prices,
            IngestionRunRepository runs,
            MarketObservationRepository observations,
            Clock clock,
            @Value("${market.feed.stale-after}") Duration staleAfter,
            ApplicationEventPublisher events) {
        this.client = client;
        this.normalizer = normalizer;
        this.prices = prices;
        this.runs = runs;
        this.observations = observations;
        this.clock = clock;
        this.staleAfter = staleAfter;
        this.events = events;
    }

    public void poll() {
        Instant startedAt = clock.instant();
        IngestionRun run;
        boolean changed = false;
        try {
            UsepFeedResponse response = client.fetch();
            MarketPrice price = normalizer.normalize(response, clock.instant());
            prices.upsert(price);
            changed = observations.save(response, price, staleAfter);

            IngestionStatus status = Duration.between(price.sourceUpdatedAt(), clock.instant())
                    .compareTo(staleAfter) > 0 ? IngestionStatus.STALE_SOURCE : IngestionStatus.SUCCESS;
            run = new IngestionRun(startedAt, clock.instant(), status, price.sourceUpdatedAt(), null);
            if (status == IngestionStatus.STALE_SOURCE) {
                LOG.warn("USEP feed responded with stale data last updated at {}", price.sourceUpdatedAt());
            } else {
                LOG.info("Stored USEP price for interval {}", price.intervalStart());
            }
        } catch (RuntimeException exception) {
            String message = exception.getClass().getSimpleName() + ": " + exception.getMessage();
            run = new IngestionRun(startedAt, clock.instant(), IngestionStatus.FAILURE, null,
                    message.substring(0, Math.min(message.length(), 1000)));
            LOG.error("USEP ingestion failed; stored prices remain available", exception);
        }
        runs.save(run);
        if (changed) {
            try {
                events.publishEvent(new MarketObservationSaved());
            } catch (RuntimeException exception) {
                LOG.error("Forecast notification failed; saved F1 data remains available", exception);
            }
        }
    }
}

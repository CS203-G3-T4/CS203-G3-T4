package sg.edu.smu.cs203.market.price;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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

    public MarketIngestionService(
            UsepClient client,
            PriceNormalizer normalizer,
            MarketPriceRepository prices,
            IngestionRunRepository runs,
            MarketObservationRepository observations,
            Clock clock,
            @Value("${market.feed.stale-after}") Duration staleAfter) {
        this.client = client;
        this.normalizer = normalizer;
        this.prices = prices;
        this.runs = runs;
        this.observations = observations;
        this.clock = clock;
        this.staleAfter = staleAfter;
    }

    public void poll() {
        Instant startedAt = clock.instant();
        IngestionRun run;
        try {
            UsepFeedResponse response = client.fetch();
            MarketPrice price = normalizer.normalize(response, clock.instant());
            prices.upsert(price);
            observations.save(response, price, staleAfter);

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
    }
}

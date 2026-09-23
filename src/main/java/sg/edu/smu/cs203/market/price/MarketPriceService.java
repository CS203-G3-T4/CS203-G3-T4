package sg.edu.smu.cs203.market.price;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MarketPriceService {

    private final MarketPriceRepository prices;
    private final IngestionRunRepository runs;
    private final Clock clock;
    private final Duration staleAfter;

    public MarketPriceService(
            MarketPriceRepository prices,
            IngestionRunRepository runs,
            Clock clock,
            @Value("${market.feed.stale-after}") Duration staleAfter) {
        this.prices = prices;
        this.runs = runs;
        this.clock = clock;
        this.staleAfter = staleAfter;
    }

    public LatestPriceResponse latest() {
        MarketPrice price = prices.findLatest().orElseThrow(NoMarketPriceException::new);
        Instant now = clock.instant();
        boolean recent = !price.sourceUpdatedAt().isAfter(now.plusSeconds(120))
                && Duration.between(price.sourceUpdatedAt(), now).compareTo(staleAfter) <= 0;
        boolean lastPollSucceeded = runs.findLatest()
                .map(run -> run.status() == IngestionStatus.SUCCESS)
                .orElse(false);
        return new LatestPriceResponse(
                price.usepSgdPerMwh(),
                "SGD_PER_MWH",
                price.forecastDemandMw(),
                price.intervalStart(),
                price.sourceUpdatedAt(),
                price.fetchedAt(),
                price.source(),
                recent && lastPollSucceeded ? PriceFreshness.LIVE : PriceFreshness.STALE);
    }

    public List<MarketPrice> history(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)
                || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Provide from and to timestamps in ascending order, at most 31 days apart");
        }
        return prices.findRange(from, to);
    }

    public FeedStatusResponse feedStatus() {
        return runs.findLatest()
                .map(run -> new FeedStatusResponse(run.status().name(), run.startedAt(),
                        run.finishedAt(), run.sourceUpdatedAt(), run.errorMessage()))
                .orElseGet(() -> new FeedStatusResponse("NEVER_POLLED", null, null, null, null));
    }
}

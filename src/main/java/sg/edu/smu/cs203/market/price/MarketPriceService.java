package sg.edu.smu.cs203.market.price;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import sg.edu.smu.cs203.common.DemoClock;

@Service
public class MarketPriceService {

    private final MarketPriceRepository prices;
    private final IngestionRunRepository runs;
    private final Clock clock;
    private final Duration staleAfter;
    private ReplayPriceService replayPrices;

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

    @Autowired(required = false)
    public void setReplayPrices(ReplayPriceService replayPrices) {
        this.replayPrices = replayPrices;
    }

    public LatestPriceResponse latest() {
        Instant now = clock.instant();
        boolean replayMode = demoClockRunning();
        MarketPrice price = replayMode
                ? replayPrices().latest(now).orElseThrow(NoMarketPriceException::new)
                : prices.findLatest().orElseThrow(NoMarketPriceException::new);
        boolean recent = !price.sourceUpdatedAt().isAfter(now.plusSeconds(120))
                && Duration.between(price.sourceUpdatedAt(), now).compareTo(staleAfter) <= 0;
        boolean lastPollSucceeded = replayMode || runs.findLatest()
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
        return demoClockRunning()
                ? replayPrices().history(from, to, clock.instant())
                : prices.findRange(from, to);
    }

    private boolean demoClockRunning() {
        return clock instanceof DemoClock demoClock && demoClock.isRunning();
    }

    private ReplayPriceService replayPrices() {
        if (replayPrices == null) {
            throw new IllegalStateException("Replay price service is required while demo mode is active");
        }
        return replayPrices;
    }

    public FeedStatusResponse feedStatus() {
        return runs.findLatest()
                .map(run -> new FeedStatusResponse(run.status().name(), run.startedAt(),
                        run.finishedAt(), run.sourceUpdatedAt(), run.errorMessage()))
                .orElseGet(() -> new FeedStatusResponse("NEVER_POLLED", null, null, null, null));
    }
}

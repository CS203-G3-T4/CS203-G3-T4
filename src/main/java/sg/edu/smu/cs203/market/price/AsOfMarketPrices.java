package sg.edu.smu.cs203.market.price;

import java.time.Clock;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import sg.edu.smu.cs203.common.DemoClock;

@Service
public class AsOfMarketPrices {

    private final MarketHistoryRepository history;
    private final ReplayPriceService replayPrices;
    private final Clock clock;

    public AsOfMarketPrices(MarketHistoryRepository history, ReplayPriceService replayPrices, Clock clock) {
        this.history = history;
        this.replayPrices = replayPrices;
        this.clock = clock;
    }

    public Map<Instant, BigDecimal> prices(Instant from, Instant asOf) {
        if (from.isAfter(asOf)) {
            return Map.of();
        }
        Map<Instant, BigDecimal> result = new LinkedHashMap<>();
        if (clock instanceof DemoClock demoClock && demoClock.isRunning()) {
            for (MarketPrice price : replayPrices.history(from, asOf.plusSeconds(1), asOf)) {
                result.put(price.intervalStart(), price.usepSgdPerMwh());
            }
        } else {
            for (MarketRevision revision : history.asOf(asOf, from)) {
                result.put(revision.periodStart(), revision.usep());
            }
        }
        return Map.copyOf(result);
    }
}

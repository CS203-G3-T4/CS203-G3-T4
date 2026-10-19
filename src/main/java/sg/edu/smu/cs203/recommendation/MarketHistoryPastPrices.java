package sg.edu.smu.cs203.recommendation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import org.springframework.stereotype.Component;

import sg.edu.smu.cs203.forecast.BaselineForecastService;
import sg.edu.smu.cs203.market.price.MarketHistoryRepository;

/**
 * F1's saved prices as they were known at asOf (the latest revision published by then), the same
 * view F3 uses for its baselines. Only reads; missing half-hours stay missing.
 */
@Component
public class MarketHistoryPastPrices implements PastPrices {

    private final MarketHistoryRepository history;

    public MarketHistoryPastPrices(MarketHistoryRepository history) {
        this.history = history;
    }

    @Override
    public Map<Instant, BigDecimal> actualPrices(Instant asOf) {
        return BaselineForecastService.values(history.asOf(asOf), asOf);
    }
}

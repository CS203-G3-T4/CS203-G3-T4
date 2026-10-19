package sg.edu.smu.cs203.recommendation;

import org.springframework.stereotype.Component;

import sg.edu.smu.cs203.market.price.FeedOutageSwitch;
import sg.edu.smu.cs203.market.price.MarketPriceService;
import sg.edu.smu.cs203.market.price.NoMarketPriceException;
import sg.edu.smu.cs203.market.price.PriceFreshness;

/** Reads F1's own LIVE/STALE decision, so F4 and the dashboard agree on what "stale" means. */
@Component
public class MarketPriceFeedStatus implements PriceFeedStatus {

    private final MarketPriceService prices;
    private final FeedOutageSwitch outage;

    public MarketPriceFeedStatus(MarketPriceService prices, FeedOutageSwitch outage) {
        this.prices = prices;
        this.outage = outage;
    }

    @Override
    public boolean simulatedOutage() {
        return outage.isOn();
    }

    @Override
    public boolean stale() {
        try {
            return prices.latest().freshness() != PriceFreshness.LIVE;
        } catch (NoMarketPriceException noPriceYet) {
            return true;
        }
    }
}

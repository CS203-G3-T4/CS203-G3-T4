package sg.edu.smu.cs203.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import sg.edu.smu.cs203.market.price.LatestPriceResponse;
import sg.edu.smu.cs203.market.price.MarketPriceService;
import sg.edu.smu.cs203.market.price.NoMarketPriceException;
import sg.edu.smu.cs203.market.price.PriceFreshness;

class MarketPriceFeedStatusTest {

    private final MarketPriceService prices = mock(MarketPriceService.class);
    private final MarketPriceFeedStatus feed = new MarketPriceFeedStatus(prices);

    @Test
    void aLivePriceIsNotStale() {
        when(prices.latest()).thenReturn(price(PriceFreshness.LIVE));

        assertThat(feed.stale()).isFalse();
    }

    @Test
    void aStalePriceIsStale() {
        when(prices.latest()).thenReturn(price(PriceFreshness.STALE));

        assertThat(feed.stale()).isTrue();
    }

    @Test
    void noPriceYetCountsAsStale() {
        when(prices.latest()).thenThrow(new NoMarketPriceException());

        assertThat(feed.stale()).isTrue();
    }

    private static LatestPriceResponse price(PriceFreshness freshness) {
        Instant at = Instant.parse("2026-09-30T00:00:00Z");
        return new LatestPriceResponse(new BigDecimal("180.5"), "SGD_PER_MWH", new BigDecimal("6500"), at, at, at,
                "NEMS_SN_SG", freshness);
    }
}

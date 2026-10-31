package sg.edu.smu.cs203.recommendation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import sg.edu.smu.cs203.market.price.AsOfMarketPrices;

class MarketHistoryPastPricesTest {

    @Test
    void recommendationConfidenceUsesTheSharedAsOfPriceSource() {
        Instant asOf = Instant.parse("2026-09-26T04:00:00Z");
        AsOfMarketPrices prices = mock(AsOfMarketPrices.class);
        MarketHistoryPastPrices pastPrices = new MarketHistoryPastPrices(prices);

        pastPrices.actualPrices(asOf);

        verify(prices).prices(asOf.minusSeconds(29 * 24 * 60 * 60), asOf);
    }
}

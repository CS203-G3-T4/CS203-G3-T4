package sg.edu.smu.cs203.market.price;

import java.math.BigDecimal;
import java.time.Instant;

public record LatestPriceResponse(
        BigDecimal price,
        String unit,
        BigDecimal forecastDemandMw,
        Instant intervalStart,
        Instant sourceUpdatedAt,
        Instant fetchedAt,
        String source,
        PriceFreshness freshness) {
}

package sg.edu.smu.cs203.market.price;

import java.math.BigDecimal;
import java.time.Instant;

public record MarketPrice(
        String source,
        Instant intervalStart,
        Instant sourceUpdatedAt,
        Instant fetchedAt,
        BigDecimal usepSgdPerMwh,
        BigDecimal forecastDemandMw,
        BigDecimal vcpSgdPerMwh) {
}

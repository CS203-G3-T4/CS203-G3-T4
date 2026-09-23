package sg.edu.smu.cs203.market.price;

import java.math.BigDecimal;

public record UsepFeedResponse(
        Long updated,
        BigDecimal usep,
        BigDecimal demand,
        BigDecimal vcp) {
}

package sg.edu.smu.cs203.market.price;

import java.math.BigDecimal;
import java.math.RoundingMode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "F1: Market prices")
@RestController
public class CurrentPriceController {
    private final MarketPriceService service;

    public CurrentPriceController(MarketPriceService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/prices/current")
    @Operation(summary = "Get the current price and cents per kWh for the dashboard")
    public CurrentPriceResponse current() {
        LatestPriceResponse latest = service.latest();
        BigDecimal cents = latest.price().divide(BigDecimal.TEN, 1, RoundingMode.HALF_UP);
        return new CurrentPriceResponse(latest, cents, latest.freshness() == PriceFreshness.STALE);
    }

    public record CurrentPriceResponse(LatestPriceResponse marketPrice,
                                       BigDecimal centsPerKwh, boolean stale) {}
}

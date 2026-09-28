package sg.edu.smu.cs203.market.price;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "F1: Market prices")
@RestController
@RequestMapping("/api/v1/market-prices")
public class MarketPriceController {

    private final MarketPriceService service;

    public MarketPriceController(MarketPriceService service) {
        this.service = service;
    }

    @GetMapping("/latest")
    @Operation(summary = "Get the latest USEP price with freshness information")
    public LatestPriceResponse latest() {
        return service.latest();
    }

    @GetMapping
    @Operation(summary = "Get stored half-hourly USEP prices for a time range")
    public List<MarketPrice> history(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return service.history(from, to);
    }

    @GetMapping("/feed-status")
    @Operation(summary = "Get the outcome of the most recent feed poll")
    public FeedStatusResponse feedStatus() {
        return service.feedStatus();
    }
}

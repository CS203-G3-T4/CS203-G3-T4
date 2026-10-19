package sg.edu.smu.cs203.market.price;

import java.time.Clock;
import java.time.Instant;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only (via /api/v1/admin/**) switch that simulates a NEMS outage (CSDT4-26/75). */
@Tag(name = "F1: Admin feed outage switch")
@RestController
@RequestMapping("/api/v1/admin/feed-outage")
public class AdminFeedOutageController {

    private final FeedOutageSwitch outage;
    private final MarketIngestionService ingestion;
    private final MarketPriceService prices;
    private final Clock clock;

    public AdminFeedOutageController(FeedOutageSwitch outage, MarketIngestionService ingestion,
            MarketPriceService prices, Clock clock) {
        this.outage = outage;
        this.ingestion = ingestion;
        this.prices = prices;
        this.clock = clock;
    }

    @GetMapping
    @Operation(summary = "Get whether the simulated NEMS outage is on")
    public OutageStatus status() {
        return current();
    }

    @PutMapping
    @Operation(summary = "Turn the simulated NEMS outage on or off, then poll the feed once so the change shows at once")
    public OutageStatus set(@Validated @RequestBody OutageRequest request) {
        if (request.enabled()) {
            outage.turnOn(clock.instant());
        } else {
            outage.turnOff();
        }
        ingestion.poll();
        return current();
    }

    private OutageStatus current() {
        return new OutageStatus(outage.isOn(), outage.onSince().orElse(null), prices.feedStatus());
    }

    public record OutageRequest(@NotNull Boolean enabled) {
    }

    public record OutageStatus(boolean enabled, Instant since, FeedStatusResponse lastPoll) {
    }
}

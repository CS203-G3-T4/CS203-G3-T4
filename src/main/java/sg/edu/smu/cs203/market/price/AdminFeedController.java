package sg.edu.smu.cs203.market.price;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "F1: Admin feed health")
@RestController
@RequestMapping("/api/v1/admin")
public class AdminFeedController {

    private final FeedHealthService service;

    public AdminFeedController(FeedHealthService service) {
        this.service = service;
    }

    @GetMapping("/feed-status")
    @Operation(summary = "Get NEMS feed health: last sync, consecutive failures, monthly uptime, fallbacks and incidents")
    public FeedHealthResponse feedStatus() {
        return service.status();
    }
}

package sg.edu.smu.cs203.recommendation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import sg.edu.smu.cs203.household.ValidationFailedException;

@Tag(name = "F4: Recommendations")
@RestController
public class RecommendationController {

    private final RecommendationService service;

    public RecommendationController(RecommendationService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/households/{householdId}/recommendations")
    @Operation(summary = "List a household's suggestions and the outcome of the latest background recompute. "
            + "Without status: open ones not snoozed, best saving first (the dashboard; CSDT4-37). With "
            + "status=ACTIVE|ACCEPTED|DISMISSED|HISTORY: one tab of the Recommendations page (CSDT4-40)")
    public RecommendationsResponse list(@PathVariable long householdId,
                                        @RequestParam(required = false) String status) {
        if (status == null || status.isBlank()) {
            return service.list(householdId);
        }
        return service.list(householdId, tab(status));
    }

    private static RecommendationTab tab(String status) {
        try {
            return RecommendationTab.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new ValidationFailedException(
                    Map.of("status", "Use ACTIVE, ACCEPTED, DISMISSED or HISTORY"));
        }
    }

    @GetMapping("/api/v1/recommendations/demo-forecast")
    @Operation(summary = "The demo forecast behind demo suggestions, for the Recommendations page's mini charts "
            + "(CSDT4-40). 404 when suggestions use F3's forecast: chart /api/v1/forecast/latest instead")
    public DemoForecastResponse demoForecast() {
        return service.demoForecast();
    }

    @PostMapping("/api/v1/households/{householdId}/recommendations/generate")
    @Operation(summary = "Recompute this household's suggestions now. Normally runs in the background on a new "
            + "price, an appliance or plan change, or every 30 minutes (CSDT4-37). Safe to repeat")
    public RecommendationsResponse generate(@PathVariable long householdId) {
        return service.generate(householdId);
    }

    @PostMapping("/api/v1/recommendations/{recId}/accept")
    @Operation(summary = "Accept a shift suggestion (CSDT4-20). Optional body {\"startTime\": \"HH:mm\"} "
            + "sets the resident's own start inside the same window. Nothing runs automatically")
    public RecommendationResponse accept(@PathVariable long recId,
                                         @Valid @RequestBody(required = false) AcceptRequest request) {
        return service.accept(recId, request);
    }

    @PostMapping("/api/v1/recommendations/{recId}/dismiss")
    @Operation(summary = "Dismiss a shift suggestion (CSDT4-20); the same run is not suggested again")
    public RecommendationResponse dismiss(@PathVariable long recId) {
        return service.dismiss(recId);
    }

    @PostMapping("/api/v1/recommendations/{recId}/got-it")
    @Operation(summary = "\"Got it\" on a price spike warning (CSDT4-38); that run is not warned about again")
    public RecommendationResponse gotIt(@PathVariable long recId) {
        return service.gotIt(recId);
    }

    @PostMapping("/api/v1/recommendations/{recId}/snooze")
    @Operation(summary = "\"Remind me later\" (CSDT4-40): hide an open suggestion until the time chosen in "
            + "{\"until\": \"HH:mm\"} (Singapore time, the next time the clock shows it). It must be before "
            + "the suggestion runs out; it then comes back by itself")
    public RecommendationResponse snooze(@PathVariable long recId, @Valid @RequestBody SnoozeRequest request) {
        return service.snooze(recId, request);
    }

    @PostMapping("/api/v1/recommendations/{recId}/remind-later")
    @Operation(summary = "One-tap \"Remind me later\" (CSDT4-38): snooze for an hour, or until an hour before "
            + "the suggestion runs out if that is sooner")
    public RecommendationResponse remindLater(@PathVariable long recId) {
        return service.remindLater(recId);
    }
}

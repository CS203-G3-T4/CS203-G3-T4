package sg.edu.smu.cs203.recommendation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "F4: Recommendations")
@RestController
public class RecommendationController {

    private final RecommendationService service;

    public RecommendationController(RecommendationService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/households/{householdId}/recommendations")
    @Operation(summary = "List a household's open suggestions (does not re-check the forecast)")
    public RecommendationsResponse list(@PathVariable long householdId) {
        return service.list(householdId);
    }

    @PostMapping("/api/v1/households/{householdId}/recommendations/generate")
    @Operation(summary = "Re-check the forecast and suggest the cheapest start for each flexible appliance "
            + "(CSDT4-19). Safe to repeat: unchanged suggestions are kept")
    public RecommendationsResponse generate(@PathVariable long householdId) {
        return service.generate(householdId);
    }

    @PostMapping("/api/v1/recommendations/{recId}/accept")
    @Operation(summary = "Accept a suggestion (CSDT4-20). Optional body {\"startTime\": \"HH:mm\"} "
            + "sets the resident's own start inside the same window. Nothing runs automatically")
    public RecommendationResponse accept(@PathVariable long recId,
                                         @Valid @RequestBody(required = false) AcceptRequest request) {
        return service.accept(recId, request);
    }

    @PostMapping("/api/v1/recommendations/{recId}/dismiss")
    @Operation(summary = "Dismiss a suggestion (CSDT4-20); the same run is not suggested again")
    public RecommendationResponse dismiss(@PathVariable long recId) {
        return service.dismiss(recId);
    }
}

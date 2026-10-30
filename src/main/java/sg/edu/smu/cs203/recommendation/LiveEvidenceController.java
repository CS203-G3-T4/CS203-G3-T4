package sg.edu.smu.cs203.recommendation;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import sg.edu.smu.cs203.household.HouseholdDirectory;
import tools.jackson.databind.JsonNode;

@RestController
public class LiveEvidenceController {
    private final LiveComparisonService comparison;
    private final LiveEvidenceRepository evidence;
    private final HouseholdDirectory households;
    private final Clock clock;
    public LiveEvidenceController(LiveComparisonService comparison,LiveEvidenceRepository evidence,HouseholdDirectory households,Clock clock) {
        this.comparison=comparison;this.evidence=evidence;this.households=households;this.clock=clock;
    }
    @GetMapping("/api/v1/admin/live-forecast-comparison")
    public Map<String,Object> report(HttpServletRequest request) {
        if(!request.isUserInRole("ADMIN")) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return comparison.report();
    }
    @GetMapping("/api/v1/households/{householdId}/recommendation-alerts")
    public List<JsonNode> alerts(@PathVariable long householdId) {households.household(householdId);return evidence.alerts(householdId,clock.instant());}
    @PostMapping("/api/v1/households/{householdId}/recommendation-alerts/{alertId}/acknowledge")
    public Map<String,Boolean> acknowledge(@PathVariable long householdId,@PathVariable long alertId) {
        households.household(householdId);
        if(!evidence.acknowledge(householdId,alertId,clock.instant())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return Map.of("acknowledged",true);
    }
}

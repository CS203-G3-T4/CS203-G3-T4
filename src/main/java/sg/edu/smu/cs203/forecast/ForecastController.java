package sg.edu.smu.cs203.forecast;

import java.time.Clock;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;

@Tag(name="F3: Forecasts and baseline evaluation")
@RestController
public class ForecastController {
    private final ForecastService service;
    private final CurrentAssessmentService assessments;
    private final ForecastRepository repository;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired private ForecastOperationsRepository operations;
    @org.springframework.beans.factory.annotation.Autowired private ForecastAccuracyService accuracyService;
    public ForecastController(ForecastService service,CurrentAssessmentService assessments,ForecastRepository repository,Clock clock) {
        this.service=service; this.assessments=assessments; this.repository=repository; this.clock=clock;
    }
    @GetMapping("/api/v1/forecast/latest")
    @Operation(summary="Read the latest saved 24-interval forecast, with stale and actionable flags")
    public ForecastTypes.View latest() { return service.latest(); }

    @GetMapping("/api/v1/forecast/current-assessment")
    @Operation(summary="Compare current observed USEP with a past-only same-half-hour reference")
    public ForecastTypes.Assessment assessment() { return assessments.current(); }
    @GetMapping("/api/v1/forecast/today")
    public Map<String,Object> today() { return service.today(); }

    @GetMapping("/api/v1/admin/forecast-accuracy")
    @Operation(summary="ADMIN only: read saved forecast errors and spike counts (no training or network calls)")
    public Map<String,Object> accuracy(@RequestParam(defaultValue="7") int days,
            @RequestParam(defaultValue="1") int horizon,@RequestParam(defaultValue="PROVISIONAL_FIRST_EVALUATION") String truthPolicy,
            HttpServletRequest request) {
        requireAdmin(request);
        if (days<1 || days>366) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"days must be 1..366");
        return accuracyService.read(days,horizon,truthPolicy,clock.instant(),service.mode());
    }
    @PostMapping(value="/api/v1/admin/forecast-baseline-validation",consumes="application/json")
    public Map<String,Object> ranking(@RequestBody tools.jackson.databind.JsonNode evidence,HttpServletRequest request) {
        requireAdmin(request); operations.saveRanking(evidence,clock.instant()); return Map.of("saved",true);
    }
    @PostMapping(value="/api/v1/admin/forecast-demand",consumes="application/json")
    public Map<String,Object> demand(@RequestBody java.util.List<ForecastOperationsRepository.Demand> rows,HttpServletRequest request) {
        requireAdmin(request); operations.saveDemand(rows,clock.instant()); return Map.of("saved",rows.size());
    }
    @PostMapping(value="/api/v1/admin/forecast-training-attempts",consumes="application/json")
    public Map<String,Object> attempt(@RequestBody tools.jackson.databind.JsonNode value,HttpServletRequest request) {
        requireAdmin(request); operations.recordAttempt(value,clock.instant()); return Map.of("saved",true);
    }
    @GetMapping("/api/v1/admin/forecast-training-attempts")
    public Map<String,Object> attempts(@RequestParam(defaultValue="20") int limit,HttpServletRequest request) {
        requireAdmin(request); return Map.of("attempts",operations.attempts(limit));
    }
    private static void requireAdmin(HttpServletRequest request) {
        if (request.getUserPrincipal()==null || !request.isUserInRole("ADMIN"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"ADMIN role required");
    }
    @ExceptionHandler({IllegalArgumentException.class,java.time.DateTimeException.class})
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,String> invalid(RuntimeException exception) { return Map.of("error",exception.getMessage()); }
}

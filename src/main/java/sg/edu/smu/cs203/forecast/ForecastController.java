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
import org.springframework.beans.factory.annotation.Autowired;
import sg.edu.smu.cs203.common.DemoClock;
import sg.edu.smu.cs203.recommendation.PriceForecastSource;

@Tag(name="F3: Forecasts and baseline evaluation")
@RestController
public class ForecastController {
    private final ForecastService service;
    private final CurrentAssessmentService assessments;
    private final ForecastRepository repository;
    private final Clock clock;
    private final ForecastOperationsRepository operations;
    private final ForecastAccuracyService accuracyService;
    private PriceForecastSource priceForecastSource;
    public ForecastController(ForecastService service,CurrentAssessmentService assessments,ForecastRepository repository,Clock clock,
            ForecastOperationsRepository operations,ForecastAccuracyService accuracyService) {
        this.service=service; this.assessments=assessments; this.repository=repository; this.clock=clock;
        this.operations=operations; this.accuracyService=accuracyService;
    }
    @Autowired(required = false)
    public void setPriceForecastSource(PriceForecastSource priceForecastSource) {
        this.priceForecastSource = priceForecastSource;
    }
    @GetMapping("/api/v1/forecast/latest")
    @Operation(summary="Read the latest saved 24-interval forecast, with stale and actionable flags")
    public ForecastTypes.View latest() {
        if (clock instanceof DemoClock demoClock && demoClock.isRunning()) {
            if (priceForecastSource == null) {
                throw new IllegalStateException("Replay forecast source is required while demo mode is active");
            }
            var lookup = priceForecastSource.forecast();
            if (!lookup.available()) {
                return new ForecastTypes.View(false,false,true,"REPLAY",lookup.unavailableReason(),null);
            }
            var forecast = lookup.forecast();
            int pointCount = Math.min(24, forecast.points().size());
            var points = java.util.stream.IntStream.range(0, pointCount)
                    .mapToObj(index -> {
                        var point = forecast.points().get(index);
                        return new ForecastTypes.Point(index + 1, point.periodStart(),
                                point.usepSgdPerMwh(), null, null);
                    })
                    .toList();
            var run = new ForecastTypes.Run(
                    "replay-"+forecast.replayDay(),forecast.asOf(),forecast.asOf(),"REPLAY",
                    "REPLAY","DEMO","replay-price-v1","REPLAY",null,
                    java.util.List.of("DEMO_REPLAY"),false,"AVAILABLE",points);
            return new ForecastTypes.View(true,false,false,"REPLAY",null,run);
        }
        return service.latest();
    }

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

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
import org.springframework.web.server.ResponseStatusException;

@Tag(name="F3: Forecasts and baseline evaluation")
@RestController
public class ForecastController {
    private final ForecastService service;
    private final CurrentAssessmentService assessments;
    private final ForecastRepository repository;
    private final Clock clock;
    public ForecastController(ForecastService service,CurrentAssessmentService assessments,ForecastRepository repository,Clock clock) {
        this.service=service; this.assessments=assessments; this.repository=repository; this.clock=clock;
    }
    @GetMapping("/api/v1/forecast/latest")
    @Operation(summary="Read the latest saved 24-interval forecast, with stale and actionable flags")
    public ForecastTypes.View latest() { return service.latest(); }

    @GetMapping("/api/v1/forecast/current-assessment")
    @Operation(summary="Compare current observed USEP with a past-only same-half-hour reference")
    public ForecastTypes.Assessment assessment() { return assessments.current(); }

    @GetMapping("/api/v1/admin/forecast-accuracy")
    @Operation(summary="ADMIN only: read saved forecast errors and spike counts (no training or network calls)")
    public Map<String,Object> accuracy(@RequestParam(defaultValue="7") int days,HttpServletRequest request) {
        if (request.getUserPrincipal()==null || !request.isUserInRole("ADMIN"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"ADMIN role required");
        if (days<1 || days>366) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"days must be 1..366");
        return repository.accuracy(days,clock.instant(),service.mode());
    }
}

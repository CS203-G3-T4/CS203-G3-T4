package sg.edu.smu.cs203.common;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import sg.edu.smu.cs203.market.price.ReplayPriceService;

@RestController
public class DemoClockController {

    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");

    private final DemoClock clock;
    private final ReplayPriceService replayPrices;
    private final DemoReplayRefreshService replayRefresh;

    public DemoClockController(DemoClock clock, ReplayPriceService replayPrices,
                               DemoReplayRefreshService replayRefresh) {
        this.clock = clock;
        this.replayPrices = replayPrices;
        this.replayRefresh = replayRefresh;
    }

    @GetMapping("/api/v1/demo/state")
    public ResponseEntity<ClockStatus> status() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(clockStatus());
    }

    @PostMapping("/api/v1/demo/start")
    public ClockStatus start(@Valid @RequestBody StartRequest request) {
        Instant start = request.date().atTime(request.time()).atZone(SINGAPORE).toInstant();
        replayPrices.loadDay(request.date());
        clock.startAt(start);
        try {
            replayRefresh.refresh();
        } catch (RuntimeException exception) {
            clock.stop();
            throw exception;
        }
        return clockStatus();
    }

    @PostMapping("/api/v1/demo/advance")
    public ClockStatus advance(@Valid @RequestBody AdvanceRequest request) {
        Instant previous = clock.instant();
        clock.advance(Duration.ofMinutes(30L * request.periods()));
        try {
            replayRefresh.refresh();
        } catch (RuntimeException exception) {
            clock.startAt(previous);
            throw exception;
        }
        return clockStatus();
    }

    @PostMapping("/api/v1/demo/stop")
    public ClockStatus stop() {
        clock.stop();
        return clockStatus();
    }

    private ClockStatus clockStatus() {
        return new ClockStatus(clock.isRunning(), clock.instant(), clock.getZone().getId());
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class,
            DateTimeException.class, ArithmeticException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalidClockOperation(RuntimeException exception) {
        return Map.of("error", exception.getMessage());
    }

    public record ClockStatus(boolean running, Instant currentTime, String zone) {
    }

    public record StartRequest(@NotNull LocalDate date, @NotNull LocalTime time) {
    }

    public record AdvanceRequest(@Positive int periods) {
    }
}

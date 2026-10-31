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

@RestController
public class DemoClockController {

    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");

    private final DemoClock clock;
    private final DemoReplayRefreshService replayRefresh;

    public DemoClockController(DemoClock clock, DemoReplayRefreshService replayRefresh) {
        this.clock = clock;
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
        return clock.transition(() -> {
            replayRefresh.start(request.date(), start);
            return clockStatus();
        });
    }

    @PostMapping("/api/v1/demo/advance")
    public ClockStatus advance(@Valid @RequestBody AdvanceRequest request) {
        return clock.transition(() -> {
            DemoClock.Snapshot previous=clock.snapshot();
            if(!previous.running()) throw new IllegalArgumentException("Cannot advance when the demo clock is stopped");
            Duration step=Duration.ofMinutes(30L * request.periods());
            Instant next=previous.instant().plus(step);
            if(!previous.instant().atZone(SINGAPORE).toLocalDate().equals(next.atZone(SINGAPORE).toLocalDate()))
                throw new IllegalArgumentException("Cannot advance beyond the loaded replay day; start another day instead");
            clock.advance(step);
            replayRefresh.refresh();
            return clockStatus();
        });
    }

    @PostMapping("/api/v1/demo/stop")
    public ClockStatus stop() {
        return clock.transition(() -> {
            clock.stop();
            return clockStatus();
        });
    }

    private ClockStatus clockStatus() {
        return new ClockStatus(clock.isRunning(), clock.instant(), clock.getZone().getId());
    }

    @ExceptionHandler({IllegalArgumentException.class,
            DateTimeException.class, ArithmeticException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalidClockOperation(RuntimeException exception) {
        return Map.of("error", exception.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, String> unavailableReplay(IllegalStateException exception) {
        return Map.of("error", "Replay demo is unavailable; check the server configuration");
    }

    public record ClockStatus(boolean running, Instant currentTime, String zone) {
    }

    public record StartRequest(@NotNull LocalDate date, @NotNull LocalTime time) {
    }

    public record AdvanceRequest(@Positive int periods) {
    }
}

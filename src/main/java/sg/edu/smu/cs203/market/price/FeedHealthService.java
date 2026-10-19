package sg.edu.smu.cs203.market.price;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Feed health for the admin view (CSDT4-25). Opens a feed_incident on the first FAILURE or
 * STALE_SOURCE poll and closes it on the next SUCCESS. Consecutive failures and the last
 * successful sync are read from ingestion_run, so they survive restarts.
 */
@Service
public class FeedHealthService {

    static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
    static final int RECENT_INCIDENTS = 10;
    private static final String SOURCE = "NEMS_SN_SG";

    private final IngestionRunRepository runs;
    private final FeedIncidentRepository incidents;
    private final Clock clock;

    public FeedHealthService(IngestionRunRepository runs, FeedIncidentRepository incidents, Clock clock) {
        this.runs = runs;
        this.incidents = incidents;
        this.clock = clock;
    }

    @EventListener
    public void onRunRecorded(IngestionRunRecorded event) {
        record(event.run());
    }

    public void record(IngestionRun run) {
        if (run.status() == IngestionStatus.SUCCESS) {
            incidents.closeOpen(run.finishedAt());
        } else if (!incidents.open(run.startedAt(), reason(run))) {
            incidents.countFailedPoll();
        }
    }

    public FeedHealthResponse status() {
        Instant now = clock.instant();
        YearMonth month = YearMonth.from(now.atZone(SINGAPORE));
        Instant monthStart = month.atDay(1).atStartOfDay(SINGAPORE).toInstant();
        Instant measuredFrom = runs.findFirstStartedAt()
                .map(first -> first.isAfter(monthStart) ? first : monthStart)
                .orElse(null);
        var latest = runs.findLatest();
        var openIncident = incidents.findOpen().map(incident -> view(incident, now)).orElse(null);
        List<FeedHealthResponse.Incident> recent = incidents.findRecent(RECENT_INCIDENTS).stream()
                .map(incident -> view(incident, now))
                .toList();
        return new FeedHealthResponse(
                SOURCE,
                latest.map(run -> run.status().name()).orElse("NEVER_POLLED"),
                latest.map(IngestionRun::finishedAt).orElse(null),
                runs.findLastSuccessAt().orElse(null),
                runs.countConsecutiveFailures(),
                month.toString(),
                measuredFrom,
                uptimePercent(measuredFrom, now),
                runs.countFallbacks(monthStart, now),
                openIncident,
                recent,
                now);
    }

    /** 100 × (1 − incident time ÷ measured time) over [measuredFrom, now), to two decimals. */
    Double uptimePercent(Instant measuredFrom, Instant now) {
        if (measuredFrom == null) {
            return null;
        }
        long measured = Duration.between(measuredFrom, now).toMillis();
        if (measured <= 0) {
            return 100.0;
        }
        long down = 0;
        for (FeedIncident incident : incidents.findOverlapping(measuredFrom, now)) {
            Instant start = incident.startedAt().isBefore(measuredFrom) ? measuredFrom : incident.startedAt();
            Instant end = incident.endedAt() == null || incident.endedAt().isAfter(now) ? now : incident.endedAt();
            down += Math.max(0, Duration.between(start, end).toMillis());
        }
        double uptime = 100.0 * (measured - Math.min(down, measured)) / measured;
        return BigDecimal.valueOf(uptime).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static FeedHealthResponse.Incident view(FeedIncident incident, Instant now) {
        Instant end = incident.open() ? now : incident.endedAt();
        return new FeedHealthResponse.Incident(incident.startedAt(), incident.endedAt(),
                Math.max(0, Duration.between(incident.startedAt(), end).toSeconds()),
                incident.reason(), incident.failedPolls(), incident.open());
    }

    private static String reason(IngestionRun run) {
        if (run.status() == IngestionStatus.STALE_SOURCE) {
            return "NEMS returned stale data last updated at " + run.sourceUpdatedAt();
        }
        return run.errorMessage() == null ? "NEMS poll failed" : run.errorMessage();
    }
}

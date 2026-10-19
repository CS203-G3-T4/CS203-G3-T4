package sg.edu.smu.cs203.market.price;

import java.time.Instant;
import java.util.List;

/**
 * Admin view of NEMS feed health (CSDT4-25/73). uptimePercent and fallbackCount cover the
 * current calendar month in Asia/Singapore; uptime is measured from the later of the month
 * start and the first recorded poll, and is null before any poll.
 */
public record FeedHealthResponse(
        String source,
        String lastPollStatus,
        Instant lastPollAt,
        Instant lastSuccessfulSyncAt,
        int consecutiveFailures,
        String month,
        Instant measuredFrom,
        Double uptimePercent,
        int fallbackCount,
        Incident openIncident,
        List<Incident> recentIncidents,
        Instant generatedAt) {

    public record Incident(
            Instant startedAt,
            Instant endedAt,
            long durationSeconds,
            String reason,
            int failedPolls,
            boolean open) {
    }
}

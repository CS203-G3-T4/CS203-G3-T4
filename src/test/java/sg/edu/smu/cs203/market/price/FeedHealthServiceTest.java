package sg.edu.smu.cs203.market.price;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class FeedHealthServiceTest {

    // 16:00 on 19 Oct in Singapore; the month started at 2026-09-30T16:00Z.
    private static final Instant NOW = Instant.parse("2026-10-19T08:00:00Z");
    private static final Instant MONTH_START = Instant.parse("2026-09-30T16:00:00Z");

    private final IngestionRunRepository runs = mock(IngestionRunRepository.class);
    private final FeedIncidentRepository incidents = mock(FeedIncidentRepository.class);
    private final FeedHealthService service = new FeedHealthService(runs, incidents, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void firstFailedPollOpensIncidentWithTheErrorAsReason() {
        when(incidents.open(any(), any())).thenReturn(true);
        Instant started = NOW.minusSeconds(5);

        service.record(new IngestionRun(started, NOW, IngestionStatus.FAILURE, null, "ResourceAccessException: Read timed out"));

        verify(incidents).open(started, "ResourceAccessException: Read timed out");
        verify(incidents, never()).countFailedPoll();
        verify(incidents, never()).closeOpen(any());
    }

    @Test
    void furtherFailedPollsAreCountedOnTheOpenIncident() {
        when(incidents.open(any(), any())).thenReturn(false);

        service.record(new IngestionRun(NOW, NOW, IngestionStatus.FAILURE, null, "down"));

        verify(incidents).countFailedPoll();
    }

    @Test
    void staleSourceOpensIncidentNamingTheSourceTime() {
        when(incidents.open(any(), any())).thenReturn(true);
        Instant updated = Instant.parse("2026-10-19T06:00:00Z");

        service.record(new IngestionRun(NOW, NOW, IngestionStatus.STALE_SOURCE, updated, null));

        verify(incidents).open(NOW, "NEMS returned stale data last updated at 2026-10-19T06:00:00Z");
    }

    @Test
    void successfulPollClosesTheOpenIncident() {
        Instant finished = NOW.plusSeconds(2);

        service.record(new IngestionRun(NOW, finished, IngestionStatus.SUCCESS, NOW, null));

        verify(incidents).closeOpen(finished);
        verify(incidents, never()).open(any(), any());
    }

    @Test
    void statusReportsMonthlyUptimeFallbacksConsecutiveFailuresAndIncidents() {
        // Measured 448 h since the month start. Down 4 h inside the month:
        // 1 h of an incident that began before the month, a closed 2 h incident and 1 h of the open one.
        FeedIncident straddling = new FeedIncident(1, MONTH_START.minusSeconds(3600), MONTH_START.plusSeconds(3600), "late", 2);
        FeedIncident closed = new FeedIncident(2, Instant.parse("2026-10-10T00:00:00Z"),
                Instant.parse("2026-10-10T02:00:00Z"), "HttpServerErrorException: 500", 24);
        FeedIncident open = new FeedIncident(3, NOW.minusSeconds(3600), null, "ResourceAccessException: Read timed out", 12);
        when(runs.findFirstStartedAt()).thenReturn(Optional.of(Instant.parse("2026-09-01T00:00:00Z")));
        when(runs.findLatest()).thenReturn(Optional.of(
                new IngestionRun(NOW.minusSeconds(60), NOW.minusSeconds(55), IngestionStatus.FAILURE, null, "timeout")));
        when(runs.findLastSuccessAt()).thenReturn(Optional.of(NOW.minusSeconds(3605)));
        when(runs.countConsecutiveFailures()).thenReturn(12);
        when(runs.countFallbacks(MONTH_START, NOW)).thenReturn(38);
        when(incidents.findOverlapping(MONTH_START, NOW)).thenReturn(List.of(straddling, closed, open));
        when(incidents.findOpen()).thenReturn(Optional.of(open));
        when(incidents.findRecent(anyInt())).thenReturn(List.of(open, closed, straddling));

        FeedHealthResponse status = service.status();

        assertThat(status.month()).isEqualTo("2026-10");
        assertThat(status.measuredFrom()).isEqualTo(MONTH_START);
        assertThat(status.uptimePercent()).isEqualTo(99.11); // 444 / 448
        assertThat(status.fallbackCount()).isEqualTo(38);
        assertThat(status.consecutiveFailures()).isEqualTo(12);
        assertThat(status.lastPollStatus()).isEqualTo("FAILURE");
        assertThat(status.lastPollAt()).isEqualTo(NOW.minusSeconds(55));
        assertThat(status.lastSuccessfulSyncAt()).isEqualTo(NOW.minusSeconds(3605));
        assertThat(status.openIncident().open()).isTrue();
        assertThat(status.openIncident().durationSeconds()).isEqualTo(3600);
        assertThat(status.recentIncidents()).extracting(FeedHealthResponse.Incident::durationSeconds)
                .containsExactly(3600L, 7200L, 7200L);
        assertThat(status.generatedAt()).isEqualTo(NOW);
    }

    @Test
    void uptimeIsMeasuredFromTheFirstPollWhenDeployedMidMonth() {
        Instant firstPoll = Instant.parse("2026-10-19T07:40:00Z");
        when(runs.findFirstStartedAt()).thenReturn(Optional.of(firstPoll));
        when(runs.findLatest()).thenReturn(Optional.of(new IngestionRun(NOW, NOW, IngestionStatus.SUCCESS, NOW, null)));
        when(incidents.findOverlapping(firstPoll, NOW)).thenReturn(List.of(
                new FeedIncident(1, firstPoll, firstPoll.plusSeconds(300), "down", 1)));

        FeedHealthResponse status = service.status();

        assertThat(status.measuredFrom()).isEqualTo(firstPoll);
        assertThat(status.uptimePercent()).isEqualTo(75.0); // 5 of 20 minutes down
        assertThat(status.openIncident()).isNull();
    }

    @Test
    void neverPolledHasNoUptimeYet() {
        when(runs.findFirstStartedAt()).thenReturn(Optional.empty());
        when(runs.findLatest()).thenReturn(Optional.empty());

        FeedHealthResponse status = service.status();

        assertThat(status.lastPollStatus()).isEqualTo("NEVER_POLLED");
        assertThat(status.uptimePercent()).isNull();
        assertThat(status.measuredFrom()).isNull();
        assertThat(status.recentIncidents()).isEmpty();
    }
}

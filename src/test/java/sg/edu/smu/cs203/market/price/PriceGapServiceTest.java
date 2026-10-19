package sg.edu.smu.cs203.market.price;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import org.mockito.ArgumentCaptor;

class PriceGapServiceTest {

    // 16:14 in Singapore: the current half-hour (08:00Z) has no price yet, so the window ends there.
    private static final Instant NOW = Instant.parse("2026-10-19T08:14:00Z");
    private static final Instant CURRENT_HALF_HOUR = Instant.parse("2026-10-19T08:00:00Z");

    private final PriceGapRepository gaps = mock(PriceGapRepository.class);
    private final IngestionRunRepository runs = mock(IngestionRunRepository.class);
    private final PriceGapService service = new PriceGapService(gaps, runs, Clock.fixed(NOW, ZoneOffset.UTC), 7);

    @Test
    void checksTheLastSevenDaysUpToTheCurrentHalfHourAndSavesTheResult() {
        Instant start = Instant.parse("2026-10-12T08:00:00Z");
        List<Instant> missing = List.of(Instant.parse("2026-10-15T00:00:00Z"), Instant.parse("2026-10-15T00:30:00Z"));
        when(runs.findFirstStartedAt()).thenReturn(Optional.of(Instant.parse("2026-10-01T00:00:00Z")));
        when(gaps.findMissingIntervals(start, CURRENT_HALF_HOUR)).thenReturn(missing);

        PriceGapCheck check = service.check();

        assertThat(check.checkedAt()).isEqualTo(NOW);
        assertThat(check.windowStart()).isEqualTo(start);
        assertThat(check.windowEnd()).isEqualTo(CURRENT_HALF_HOUR);
        assertThat(check.missingIntervals()).isEqualTo(missing);
        verify(gaps).save(check);
    }

    @Test
    void windowStartsAtTheFirstPolledHalfHourWhenDeployedRecently() {
        // First poll at 07:40Z captured the 07:30Z interval, so that is where the window starts.
        when(runs.findFirstStartedAt()).thenReturn(Optional.of(Instant.parse("2026-10-19T07:40:00Z")));
        when(gaps.findMissingIntervals(any(), any())).thenReturn(List.of());

        PriceGapCheck check = service.check();

        assertThat(check.windowStart()).isEqualTo(Instant.parse("2026-10-19T07:30:00Z"));
        verify(gaps).findMissingIntervals(Instant.parse("2026-10-19T07:30:00Z"), CURRENT_HALF_HOUR);
    }

    @Test
    void nothingIsMissingBeforeTheFirstPoll() {
        when(runs.findFirstStartedAt()).thenReturn(Optional.empty());

        PriceGapCheck check = service.check();

        assertThat(check.windowStart()).isEqualTo(CURRENT_HALF_HOUR);
        assertThat(check.missingIntervals()).isEmpty();
        verify(gaps, never()).findMissingIntervals(any(), any());
        ArgumentCaptor<PriceGapCheck> saved = ArgumentCaptor.forClass(PriceGapCheck.class);
        verify(gaps).save(saved.capture());
        assertThat(saved.getValue().windowEnd()).isEqualTo(CURRENT_HALF_HOUR);
    }

    @Test
    void consecutiveMissingHalfHoursMergeIntoRanges() {
        PriceGapCheck check = new PriceGapCheck(NOW, NOW, NOW, List.of(
                Instant.parse("2026-10-15T00:00:00Z"),
                Instant.parse("2026-10-15T00:30:00Z"),
                Instant.parse("2026-10-15T01:00:00Z"),
                Instant.parse("2026-10-16T12:30:00Z")));

        assertThat(check.ranges()).containsExactly(
                new PriceGapCheck.Range(Instant.parse("2026-10-15T00:00:00Z"), Instant.parse("2026-10-15T01:30:00Z"), 3),
                new PriceGapCheck.Range(Instant.parse("2026-10-16T12:30:00Z"), Instant.parse("2026-10-16T13:00:00Z"), 1));
    }

    @Test
    void floorsToTheStartOfTheHalfHour() {
        assertThat(PriceGapService.floorToHalfHour(Instant.parse("2026-10-19T08:29:59Z")))
                .isEqualTo(Instant.parse("2026-10-19T08:00:00Z"));
        assertThat(PriceGapService.floorToHalfHour(Instant.parse("2026-10-19T08:30:00Z")))
                .isEqualTo(Instant.parse("2026-10-19T08:30:00Z"));
    }
}

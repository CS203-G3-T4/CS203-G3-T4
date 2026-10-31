package sg.edu.smu.cs203.market.price;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import sg.edu.smu.cs203.common.DemoClock;

class AsOfMarketPricesTest {

    private static final Instant AS_OF = Instant.parse("2026-09-26T04:00:00Z");
    private static final Instant PERIOD = Instant.parse("2026-09-26T03:30:00Z");
    private static final Instant FROM = AS_OF.minusSeconds(29 * 24 * 60 * 60);

    private final MarketHistoryRepository history = mock(MarketHistoryRepository.class);
    private final ReplayPriceService replayPrices = mock(ReplayPriceService.class);

    @Test
    void demoModeReadsOnlyReplayPricesAsOfTheSharedClock() {
        when(replayPrices.history(FROM, AS_OF.plusSeconds(1), AS_OF))
                .thenReturn(List.of(replayPrice()));
        DemoClock clock = new DemoClock(Clock.fixed(AS_OF, ZoneId.of("Asia/Singapore")));
        clock.startAt(AS_OF);

        var result = new AsOfMarketPrices(history, replayPrices, clock).prices(FROM, AS_OF);

        assertThat(result).containsKey(PERIOD);
        assertThat(result.get(PERIOD)).isEqualByComparingTo("120");
        verify(replayPrices).history(FROM, AS_OF.plusSeconds(1), AS_OF);
        verify(history, never()).asOf(any(Instant.class), any(Instant.class));
    }

    @Test
    void normalModeContinuesReadingRevisionAwareLiveHistory() {
        when(history.asOf(AS_OF, FROM)).thenReturn(List.of(new MarketRevision(1L, "EMC", PERIOD,
                PERIOD, PERIOD, new BigDecimal("120"), null, null, "FINAL", "VERIFIED")));
        Clock clock = Clock.fixed(AS_OF, ZoneId.of("Asia/Singapore"));

        var result = new AsOfMarketPrices(history, replayPrices, clock).prices(FROM, AS_OF);

        assertThat(result).containsKey(PERIOD);
        assertThat(result.get(PERIOD)).isEqualByComparingTo("120");
        verify(history).asOf(AS_OF, FROM);
        verify(replayPrices, never()).history(any(Instant.class), any(Instant.class), any(Instant.class));
    }

    @Test
    void futureOnlyRangesReturnNoPrices() {
        var result = new AsOfMarketPrices(history, replayPrices, Clock.fixed(AS_OF,
                ZoneId.of("Asia/Singapore"))).prices(AS_OF.plusSeconds(1), AS_OF);

        assertThat(result).isEmpty();
        verify(history, never()).asOf(any(Instant.class), any(Instant.class));
        verify(replayPrices, never()).history(any(Instant.class), any(Instant.class), any(Instant.class));
    }

    private static MarketPrice replayPrice() {
        return new MarketPrice("REPLAY", PERIOD, PERIOD, PERIOD, new BigDecimal("120"), null, null);
    }
}

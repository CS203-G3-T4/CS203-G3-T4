package sg.edu.smu.cs203.market.price;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;

import org.junit.jupiter.api.Test;
import sg.edu.smu.cs203.common.DemoClock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class MarketPriceServiceTest {

    private final Instant now = Instant.parse("2026-09-23T06:35:00Z");
    private final MarketPriceRepository prices = mock(MarketPriceRepository.class);
    private final IngestionRunRepository runs = mock(IngestionRunRepository.class);
    private final MarketPriceService service = new MarketPriceService(
            prices, runs, Clock.fixed(now, ZoneOffset.UTC), Duration.ofMinutes(40));

    @Test
    void returnsLivePriceAfterSuccessfulRecentPoll() {
        when(prices.findLatest()).thenReturn(Optional.of(price()));
        when(runs.findLatest()).thenReturn(Optional.of(run(IngestionStatus.SUCCESS)));

        LatestPriceResponse latest = service.latest();

        assertThat(latest.freshness()).isEqualTo(PriceFreshness.LIVE);
        assertThat(latest.price()).isEqualByComparingTo("180.8100");
    }

    @Test
    void labelsLastKnownPriceStaleAfterFeedFailure() {
        when(prices.findLatest()).thenReturn(Optional.of(price()));
        when(runs.findLatest()).thenReturn(Optional.of(run(IngestionStatus.FAILURE)));

        assertThat(service.latest().freshness()).isEqualTo(PriceFreshness.STALE);
    }

    @Test
    void labelsOldPriceStaleEvenAfterSuccessfulPoll() {
        MarketPrice oldPrice = new MarketPrice(PriceNormalizer.SOURCE,
                now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(2)), now,
                new BigDecimal("180.8100"), new BigDecimal("7385.000"), null);
        when(prices.findLatest()).thenReturn(Optional.of(oldPrice));
        when(runs.findLatest()).thenReturn(Optional.of(run(IngestionStatus.SUCCESS)));

        assertThat(service.latest().freshness()).isEqualTo(PriceFreshness.STALE);
    }

    @Test
    void reportsUnavailabilityWhenNothingWasEverStored() {
        when(prices.findLatest()).thenReturn(Optional.empty());

        assertThatThrownBy(service::latest).isInstanceOf(NoMarketPriceException.class);
    }

    @Test
    void demoReadsOnlyReplayRowsAndBoundsThemAtTheSimulatedClock() {
        DemoClock demoClock = new DemoClock(Clock.fixed(now.plusSeconds(3600), ZoneOffset.UTC));
        demoClock.startAt(now);
        ReplayPriceService replay = mock(ReplayPriceService.class);
        MarketPriceService demoService = new MarketPriceService(
                prices, runs, demoClock, Duration.ofMinutes(40));
        demoService.setReplayPrices(replay);
        MarketPrice replayPrice = new MarketPrice("REPLAY", now, now, now,
                new BigDecimal("95.0000"), new BigDecimal("7000.000"), null);
        when(replay.latest(now)).thenReturn(Optional.of(replayPrice));

        LatestPriceResponse latest = demoService.latest();
        assertThat(latest.source()).isEqualTo("REPLAY");
        assertThat(latest.price()).isEqualByComparingTo("95.0000");
        verify(replay).latest(now);
        verifyNoInteractions(prices, runs);

        Instant from = now.minus(Duration.ofHours(1));
        Instant to = now.plus(Duration.ofHours(1));
        when(replay.history(from, to, now)).thenReturn(List.of(replayPrice));
        assertThat(demoService.history(from, to)).containsExactly(replayPrice);
        verify(replay).history(from, to, now);
    }

    private MarketPrice price() {
        return new MarketPrice(PriceNormalizer.SOURCE, Instant.parse("2026-09-23T06:30:00Z"),
                Instant.parse("2026-09-23T06:31:00Z"), now,
                new BigDecimal("180.8100"), new BigDecimal("7385.000"), null);
    }

    private IngestionRun run(IngestionStatus status) {
        return new IngestionRun(now.minusSeconds(10), now, status,
                status == IngestionStatus.SUCCESS ? now.minusSeconds(240) : null, null);
    }
}

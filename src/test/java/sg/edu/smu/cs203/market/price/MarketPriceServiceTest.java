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

import org.junit.jupiter.api.Test;

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

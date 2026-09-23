package sg.edu.smu.cs203.market.price;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MarketIngestionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-23T06:35:00Z");

    @Test
    void storesPriceAndRecordsSuccessfulPoll() {
        UsepClient client = mock(UsepClient.class);
        MarketPriceRepository prices = mock(MarketPriceRepository.class);
        IngestionRunRepository runs = mock(IngestionRunRepository.class);
        when(client.fetch()).thenReturn(feedAt(NOW.minusSeconds(240)));
        MarketIngestionService service = service(client, prices, runs);

        service.poll();

        ArgumentCaptor<MarketPrice> storedPrice = ArgumentCaptor.forClass(MarketPrice.class);
        verify(prices).upsert(storedPrice.capture());
        assertThat(storedPrice.getValue().intervalStart())
                .isEqualTo(Instant.parse("2026-09-23T06:30:00Z"));
        ArgumentCaptor<IngestionRun> storedRun = ArgumentCaptor.forClass(IngestionRun.class);
        verify(runs).save(storedRun.capture());
        assertThat(storedRun.getValue().status()).isEqualTo(IngestionStatus.SUCCESS);
    }

    @Test
    void recordsStaleSourceWhenFeedRespondsWithOldTimestamp() {
        UsepClient client = mock(UsepClient.class);
        MarketPriceRepository prices = mock(MarketPriceRepository.class);
        IngestionRunRepository runs = mock(IngestionRunRepository.class);
        when(client.fetch()).thenReturn(feedAt(NOW.minus(Duration.ofHours(2))));

        service(client, prices, runs).poll();

        ArgumentCaptor<IngestionRun> storedRun = ArgumentCaptor.forClass(IngestionRun.class);
        verify(runs).save(storedRun.capture());
        assertThat(storedRun.getValue().status()).isEqualTo(IngestionStatus.STALE_SOURCE);
        verify(prices).upsert(any(MarketPrice.class));
    }

    @Test
    void recordsFailureAndKeepsStoredPriceWhenFeedIsDown() {
        UsepClient client = mock(UsepClient.class);
        MarketPriceRepository prices = mock(MarketPriceRepository.class);
        IngestionRunRepository runs = mock(IngestionRunRepository.class);
        when(client.fetch()).thenThrow(new IllegalStateException("feed is down"));
        MarketIngestionService service = service(client, prices, runs);

        service.poll();

        ArgumentCaptor<IngestionRun> captured = ArgumentCaptor.forClass(IngestionRun.class);
        verify(runs).save(captured.capture());
        assertThat(captured.getValue().status()).isEqualTo(IngestionStatus.FAILURE);
        assertThat(captured.getValue().errorMessage()).contains("feed is down");
        verifyNoInteractions(prices);
    }

    private MarketIngestionService service(UsepClient client, MarketPriceRepository prices,
            IngestionRunRepository runs) {
        return new MarketIngestionService(client, new PriceNormalizer(), prices, runs,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(40));
    }

    private UsepFeedResponse feedAt(Instant updatedAt) {
        return new UsepFeedResponse(updatedAt.getEpochSecond(), new BigDecimal("180.81"),
                new BigDecimal("7385"), null);
    }
}

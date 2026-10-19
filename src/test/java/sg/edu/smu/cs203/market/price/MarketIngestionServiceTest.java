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
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    void concurrentScheduledAndManualPollsDoNotOverlap() throws Exception {
        UsepClient client = mock(UsepClient.class);
        IngestionRunRepository runs = mock(IngestionRunRepository.class);
        when(runs.findLatest()).thenReturn(Optional.empty());
        CountDownLatch firstFetchStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstFetch = new CountDownLatch(1);
        CountDownLatch secondPollStarted = new CountDownLatch(1);
        CountDownLatch secondPollFinished = new CountDownLatch(1);
        AtomicInteger activeFetches = new AtomicInteger();
        AtomicInteger maximumConcurrentFetches = new AtomicInteger();
        when(client.fetch()).thenAnswer(invocation -> {
            int active = activeFetches.incrementAndGet();
            maximumConcurrentFetches.accumulateAndGet(active, Math::max);
            try {
                if (active == 1) {
                    firstFetchStarted.countDown();
                    if (!releaseFirstFetch.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release the first test fetch");
                    }
                }
                return feedAt(NOW.minusSeconds(240));
            } finally {
                activeFetches.decrementAndGet();
            }
        });

        MarketIngestionService service = service(client, mock(MarketPriceRepository.class), runs);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var firstPoll = executor.submit(service::poll);
            assertThat(firstFetchStarted.await(5, TimeUnit.SECONDS)).isTrue();
            var secondPoll = executor.submit(() -> {
                secondPollStarted.countDown();
                service.poll();
                secondPollFinished.countDown();
            });

            assertThat(secondPollStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(secondPollFinished.await(100, TimeUnit.MILLISECONDS)).isFalse();

            releaseFirstFetch.countDown();
            firstPoll.get(5, TimeUnit.SECONDS);
            secondPoll.get(5, TimeUnit.SECONDS);
            assertThat(maximumConcurrentFetches).hasValue(1);
        } finally {
            releaseFirstFetch.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void publishesEverySavedPollOutcomeForFeedHealth() {
        UsepClient client = mock(UsepClient.class);
        IngestionRunRepository runs = mock(IngestionRunRepository.class);
        var events = mock(org.springframework.context.ApplicationEventPublisher.class);
        when(client.fetch()).thenThrow(new IllegalStateException("feed is down"))
                .thenReturn(feedAt(NOW.minusSeconds(240)));
        var service = new MarketIngestionService(client, new PriceNormalizer(), mock(MarketPriceRepository.class), runs,
                mock(MarketObservationRepository.class), Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(40), events);

        service.poll();
        service.poll();

        ArgumentCaptor<IngestionRun> saved = ArgumentCaptor.forClass(IngestionRun.class);
        verify(runs, org.mockito.Mockito.times(2)).save(saved.capture());
        ArgumentCaptor<IngestionRunRecorded> published = ArgumentCaptor.forClass(IngestionRunRecorded.class);
        verify(events, org.mockito.Mockito.times(2)).publishEvent(published.capture());
        assertThat(published.getAllValues()).extracting(IngestionRunRecorded::run)
                .containsExactlyElementsOf(saved.getAllValues());
        assertThat(published.getAllValues()).extracting(event -> event.run().status())
                .containsExactly(IngestionStatus.FAILURE, IngestionStatus.SUCCESS);
    }

    @Test
    void feedHealthListenerFailureDoesNotBreakIngestion() {
        UsepClient client = mock(UsepClient.class);
        IngestionRunRepository runs = mock(IngestionRunRepository.class);
        var events = mock(org.springframework.context.ApplicationEventPublisher.class);
        when(client.fetch()).thenThrow(new IllegalStateException("feed is down"));
        org.mockito.Mockito.doThrow(new IllegalStateException("incident table missing"))
                .when(events).publishEvent(any(IngestionRunRecorded.class));

        new MarketIngestionService(client, new PriceNormalizer(), mock(MarketPriceRepository.class), runs,
                mock(MarketObservationRepository.class), Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(40), events)
                .poll();

        verify(runs).save(any(IngestionRun.class));
    }

    private MarketIngestionService service(UsepClient client, MarketPriceRepository prices,
            IngestionRunRepository runs) {
        return new MarketIngestionService(client, new PriceNormalizer(), prices, runs, mock(MarketObservationRepository.class),
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(40), mock(org.springframework.context.ApplicationEventPublisher.class));
    }

    private UsepFeedResponse feedAt(Instant updatedAt) {
        return new UsepFeedResponse(updatedAt.getEpochSecond(), new BigDecimal("180.81"),
                new BigDecimal("7385"), null);
    }

    @Test
    void notifiesForecastOnlyAfterCanonicalSaveAndPollStatus() {
        UsepClient client=mock(UsepClient.class);
        MarketPriceRepository prices=mock(MarketPriceRepository.class);
        IngestionRunRepository runs=mock(IngestionRunRepository.class);
        MarketObservationRepository observations=mock(MarketObservationRepository.class);
        var events=mock(org.springframework.context.ApplicationEventPublisher.class);
        when(client.fetch()).thenReturn(feedAt(NOW.minusSeconds(240)));
        when(observations.save(any(),any(),any())).thenReturn(true,false);
        when(runs.findLatest()).thenReturn(java.util.Optional.empty(),java.util.Optional.of(
                new IngestionRun(NOW,NOW,IngestionStatus.SUCCESS,NOW,null)));
        var service=new MarketIngestionService(client,new PriceNormalizer(),prices,runs,observations,
                Clock.fixed(NOW,ZoneOffset.UTC),Duration.ofMinutes(40),events);
        service.poll();
        var order=org.mockito.Mockito.inOrder(observations,runs,events);
        order.verify(observations).save(any(),any(),any());
        order.verify(runs).save(any());
        order.verify(events).publishEvent(any(MarketObservationSaved.class));
        service.poll();
        org.mockito.Mockito.verify(events,org.mockito.Mockito.times(1)).publishEvent(any(MarketObservationSaved.class));
    }

    @Test
    void importedDuplicateTriggersForecastAfterFirstSuccessfulPollAndRecovery() {
        var client=mock(UsepClient.class);
        var runs=mock(IngestionRunRepository.class);
        var events=mock(org.springframework.context.ApplicationEventPublisher.class);
        when(client.fetch()).thenReturn(feedAt(NOW.minusSeconds(240)));
        when(runs.findLatest()).thenReturn(java.util.Optional.empty(),java.util.Optional.of(
                new IngestionRun(NOW,NOW,IngestionStatus.FAILURE,null,"outage")));
        var service=new MarketIngestionService(client,new PriceNormalizer(),mock(MarketPriceRepository.class),runs,
                mock(MarketObservationRepository.class),Clock.fixed(NOW,ZoneOffset.UTC),Duration.ofMinutes(40),events);
        service.poll(); service.poll();
        org.mockito.Mockito.verify(events,org.mockito.Mockito.times(2)).publishEvent(any(MarketObservationSaved.class));
    }
}

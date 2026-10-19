package sg.edu.smu.cs203.market.price;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

/**
 * CSDT4-142: when NEMS errors, times out or sends invalid JSON, the latest-price endpoints keep
 * serving the last stored price and mark it stale, and the failure reaches feed health.
 */
class FeedFailureFallbackTest {

    private static final String FEED_URL = "https://nems.test/api/status.json";
    private static final Instant NOW = Instant.parse("2026-10-19T08:05:00Z");
    // A recent stored price, so only the failed poll makes it stale.
    private static final MarketPrice STORED = new MarketPrice("NEMS_SN_SG", Instant.parse("2026-10-19T07:30:00Z"),
            Instant.parse("2026-10-19T08:01:00Z"), Instant.parse("2026-10-19T08:02:00Z"),
            new BigDecimal("190.7500"), new BigDecimal("7129.000"), null);

    static Stream<Arguments> nemsFailures() {
        return Stream.of(
                Arguments.of("server error", (Consumer<ResponseActions>) actions -> actions.andRespond(withServerError()),
                        "500 Internal Server Error"),
                Arguments.of("timeout", (Consumer<ResponseActions>) actions -> actions.andRespond(request -> {
                    throw new SocketTimeoutException("Read timed out");
                }), "ResourceAccessException"),
                Arguments.of("invalid JSON", (Consumer<ResponseActions>) actions -> actions.andRespond(
                        withSuccess("{\"updated\": \"not a number\", ", MediaType.APPLICATION_JSON)),
                        "RestClientException"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nemsFailures")
    void latestPriceEndpointsServeLastPriceAsStaleWhenNemsFails(String name, Consumer<ResponseActions> failure,
            String errorType) throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer nems = MockRestServiceServer.bindTo(builder).build();
        failure.accept(nems.expect(requestTo(FEED_URL)));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        MarketPriceRepository prices = mock(MarketPriceRepository.class);
        when(prices.findLatest()).thenReturn(Optional.of(STORED));
        InMemoryRuns runs = new InMemoryRuns();
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

        new MarketIngestionService(new UsepClient(builder.build(), FEED_URL), new PriceNormalizer(), prices, runs,
                mock(MarketObservationRepository.class), clock, Duration.ofMinutes(40), events).poll();

        nems.verify();
        verify(prices, never()).upsert(any());
        assertThat(runs.saved).singleElement().satisfies(run -> {
            assertThat(run.status()).isEqualTo(IngestionStatus.FAILURE);
            assertThat(run.errorMessage()).contains(errorType);
        });
        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(published.capture());
        assertThat(published.getValue()).isEqualTo(new IngestionRunRecorded(runs.saved.get(0)));

        MarketPriceService service = new MarketPriceService(prices, runs, clock, Duration.ofMinutes(40));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MarketPriceController(service), new CurrentPriceController(service))
                .build();
        mvc.perform(get("/api/v1/market-prices/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(190.75))
                .andExpect(jsonPath("$.intervalStart").value("2026-10-19T07:30:00Z"))
                .andExpect(jsonPath("$.freshness").value("STALE"));
        mvc.perform(get("/api/v1/prices/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marketPrice.price").value(190.75))
                .andExpect(jsonPath("$.stale").value(true));
    }

    /** Keeps poll outcomes in memory so the price service sees the failed poll. */
    private static final class InMemoryRuns extends IngestionRunRepository {
        final List<IngestionRun> saved = new ArrayList<>();

        InMemoryRuns() {
            super(null);
        }

        @Override
        public void save(IngestionRun run) {
            saved.add(run);
        }

        @Override
        public Optional<IngestionRun> findLatest() {
            return saved.isEmpty() ? Optional.empty() : Optional.of(saved.get(saved.size() - 1));
        }
    }
}

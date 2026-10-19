package sg.edu.smu.cs203.market.price;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

/**
 * CSDT4-76: turning the outage switch on makes the dashboard price stale on the very next poll
 * (the switch polls at once), and turning it off brings live data back without a restart.
 */
class FeedOutageSwitchTest {

    private static final String FEED_URL = "https://nems.test/api/status.json";
    private static final Instant NOW = Instant.parse("2026-10-19T08:05:00Z");
    private static final String NEMS_BODY = "{\"updated\": " + NOW.minusSeconds(240).getEpochSecond()
            + ", \"usep\": 190.75, \"demand\": 7129, \"vcp\": null}";

    @Test
    void switchMakesPriceStaleAtOnceAndLiveDataReturnsWithoutRestart() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer nems = MockRestServiceServer.bindTo(builder).build();
        // NEMS is only called by the two healthy polls; the outage poll never reaches it.
        nems.expect(times(2), requestTo(FEED_URL)).andRespond(withSuccess(NEMS_BODY, MediaType.APPLICATION_JSON));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        FeedOutageSwitch outage = new FeedOutageSwitch();
        InMemoryPrices prices = new InMemoryPrices();
        InMemoryRuns runs = new InMemoryRuns();
        MarketIngestionService ingestion = new MarketIngestionService(new UsepClient(builder.build(), FEED_URL, outage),
                new PriceNormalizer(), prices, runs, mock(MarketObservationRepository.class), clock,
                Duration.ofMinutes(40), mock(ApplicationEventPublisher.class));
        MarketPriceService priceService = new MarketPriceService(prices, runs, clock, Duration.ofMinutes(40));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new AdminFeedOutageController(outage, ingestion, priceService, clock),
                new CurrentPriceController(priceService)).build();

        ingestion.poll();
        mvc.perform(get("/api/v1/prices/current"))
                .andExpect(jsonPath("$.stale").value(false))
                .andExpect(jsonPath("$.marketPrice.price").value(190.75));

        mvc.perform(put("/api/v1/admin/feed-outage").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.since").value("2026-10-19T08:05:00Z"))
                .andExpect(jsonPath("$.lastPoll.status").value("FAILURE"))
                .andExpect(jsonPath("$.lastPoll.errorMessage").value(
                        "SimulatedFeedOutageException: Simulated NEMS outage (admin outage switch is on)"));
        mvc.perform(get("/api/v1/prices/current"))
                .andExpect(jsonPath("$.stale").value(true))
                .andExpect(jsonPath("$.marketPrice.price").value(190.75));

        mvc.perform(put("/api/v1/admin/feed-outage").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.lastPoll.status").value("SUCCESS"));
        mvc.perform(get("/api/v1/prices/current"))
                .andExpect(jsonPath("$.stale").value(false));
        mvc.perform(get("/api/v1/admin/feed-outage")).andExpect(jsonPath("$.enabled").value(false));

        nems.verify();
        assertThat(runs.saved).extracting(IngestionRun::status)
                .containsExactly(IngestionStatus.SUCCESS, IngestionStatus.FAILURE, IngestionStatus.SUCCESS);
    }

    @Test
    void turningOnTwiceKeepsTheOriginalStartTime() {
        FeedOutageSwitch outage = new FeedOutageSwitch();

        outage.turnOn(NOW);
        outage.turnOn(NOW.plusSeconds(60));

        assertThat(outage.onSince()).contains(NOW);
        outage.turnOff();
        assertThat(outage.isOn()).isFalse();
        assertThat(outage.onSince()).isEmpty();
    }

    @Test
    void missingEnabledFlagIsRejected() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdminFeedOutageController(new FeedOutageSwitch(),
                mock(MarketIngestionService.class), mock(MarketPriceService.class), Clock.fixed(NOW, ZoneOffset.UTC)))
                .build();

        mvc.perform(put("/api/v1/admin/feed-outage").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    private static final class InMemoryPrices extends MarketPriceRepository {
        private MarketPrice latest;

        InMemoryPrices() {
            super(null);
        }

        @Override
        public void upsert(MarketPrice price) {
            latest = price;
        }

        @Override
        public Optional<MarketPrice> findLatest() {
            return Optional.ofNullable(latest);
        }
    }

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

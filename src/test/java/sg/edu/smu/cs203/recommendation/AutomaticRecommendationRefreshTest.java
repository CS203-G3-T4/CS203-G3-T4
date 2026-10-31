package sg.edu.smu.cs203.recommendation;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import sg.edu.smu.cs203.forecast.*;
import sg.edu.smu.cs203.household.*;
import sg.edu.smu.cs203.household.appliance.*;
import sg.edu.smu.cs203.market.EnergyMarketServiceApplication;
import sg.edu.smu.cs203.market.price.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real transactions, ownership checks and HTTP security; no manual Generate request in the refresh test. */
@EnabledIfEnvironmentVariable(named="WATTLY_TEST_DB_URL",matches=".+")
@SpringBootTest(classes={EnergyMarketServiceApplication.class,AutomaticRecommendationRefreshTest.Time.class},
        properties={"market.feed.enabled=false","weather.daily.enabled=false","forecast.mode=LIVE",
            "forecast.period-mapping-evidence=AUTOMATIC_REFRESH_TEST_FIXTURE","forecast.import-file=",
            "recommendation.forecast-source=f3","recommendation.auto-recompute=true",
            "recommendation.recompute-cron=0 0 0 1 1 *"})
@AutoConfigureMockMvc
class AutomaticRecommendationRefreshTest {
    static final Instant NOW=Instant.parse("2026-09-30T00:10:00Z");
    static final String SCHEMA="auto_rec_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",()->System.getenv("WATTLY_TEST_DB_URL"));
        p.add("spring.datasource.username",()->System.getenv().getOrDefault("WATTLY_TEST_DB_USER","wattly_test"));
        p.add("spring.datasource.password",()->System.getenv().getOrDefault("WATTLY_TEST_DB_PASSWORD",""));
        p.add("spring.flyway.default-schema",()->SCHEMA);
        p.add("spring.datasource.hikari.connection-init-sql",()->"SET search_path TO "+SCHEMA);
    }
    @TestConfiguration static class Time {
        @Bean @Primary Clock testClock() { return Clock.fixed(NOW,ZoneId.of("Asia/Singapore")); }
    }
    @Autowired HouseholdRepository households;
    @Autowired ApplianceRepository appliances;
    @Autowired MarketHistoryRepository history;
    @Autowired MarketPriceRepository currentPrices;
    @Autowired IngestionRunRepository ingestion;
    @Autowired ForecastRepository forecasts;
    @Autowired RecommendationRepository recommendations;
    @Autowired RecommendationRecomputeJob job;
    @Autowired ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    long home, other, simulated;

    @BeforeEach void setUp() {
        SecurityContextHolder.clearContext();
        // Each test uses fresh household IDs and accounts in its isolated PostgreSQL schema.
        home=households.insert(new Household(null,"Automatic home",DwellingType.HDB_4_ROOM,4,
                PlanType.PRICE_LINKED,null,null,false,NOW,NOW));
        other=households.insert(new Household(null,"Other home",DwellingType.HDB_4_ROOM,4,
                PlanType.PRICE_LINKED,null,null,false,NOW,NOW));
        simulated=households.insert(new Household(null,"Protected simulation",DwellingType.HDB_4_ROOM,4,
                PlanType.PRICE_LINKED,null,null,true,NOW,NOW));
        addAppliance(home,"Flexible",Flexibility.FLEXIBLE,true);
        addAppliance(home,"Disabled",Flexibility.FLEXIBLE,false);
        addAppliance(home,"Fixed",Flexibility.FIXED,true);
        addAppliance(other,"Other flexible",Flexibility.FLEXIBLE,true);
        jdbc.update("INSERT INTO user_account(username,password_hash,role,household_id) VALUES (?,?,?,?)",
                "owner-"+home,"unused-test-hash","RESIDENT",home);
        var boundary=BaselineForecastService.floor(NOW);
        history.save(new MarketRevision(0,"NEMS_SN_SG",boundary,boundary.plusSeconds(60),boundary.plusSeconds(120),
                BigDecimal.valueOf(200),null,null,"PROVISIONAL","EMC_PERIOD"),"AUTO_TEST",boundary.toString(),"{}");
        ingestion.save(new IngestionRun(NOW,NOW,IngestionStatus.SUCCESS,boundary.plusSeconds(60),null));
        currentPrices.upsert(new MarketPrice("NEMS_SN_SG",boundary,boundary.plusSeconds(60),NOW,
                BigDecimal.valueOf(200),BigDecimal.valueOf(6000),BigDecimal.valueOf(225)));
    }
    void addAppliance(long id,String name,Flexibility flexibility,boolean enabled) {
        appliances.insert(new Appliance(null,id,name,ApplianceType.TUMBLE_DRYER,BigDecimal.ONE,30,flexibility,
                LocalTime.of(9,0),LocalTime.of(20,30),LocalTime.of(19,0),7,enabled,null,NOW,NOW));
    }
    ForecastTypes.Run run(String id,int cheapHour,String mode) {
        var targets=BaselineForecastService.targets(NOW);
        var points=IntStream.range(0,24).mapToObj(i->new ForecastTypes.Point(i+1,targets.get(i),
                BigDecimal.valueOf(targets.get(i).atZone(ZoneId.of("Asia/Singapore")).getHour()==cheapHour
                    && targets.get(i).atZone(ZoneId.of("Asia/Singapore")).getMinute()==0?50:300),null,null)).toList();
        return new ForecastTypes.Run(id,NOW,NOW,id,mode,"BASELINE","B3-v1","B3",null,List.of(),false,"AVAILABLE",points);
    }
    boolean save(ForecastTypes.Run run) {
        return forecasts.save(run,"[]",Map.of("B3",run.points()),null);
    }

    @Test void onlyCommittedNewForecastsRefreshAndLaterForecastsReplaceAdviceWithoutGenerate() {
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        var first=run("first-"+home,10,"LIVE");
        new TransactionTemplate(transactions).executeWithoutResult(status->{save(first);status.setRollbackOnly();});
        assertThat(recommendations.findRefresh(home)).isEmpty();
        assertThat(save(first)).isTrue();
        assertThat(recommendations.findRefresh(home).orElseThrow().forecastSource()).isEqualTo(PriceForecast.F3);
        var initial=recommendations.findActive(home);
        assertThat(initial).hasSize(1); // Disabled and fixed appliances are excluded.
        assertThat(initial.getFirst().suggestedStart()).isEqualTo(Instant.parse("2026-09-30T02:00:00Z"));
        assertThat(save(first)).isFalse();
        assertThat(recommendations.findActive(home).getFirst().id()).isEqualTo(initial.getFirst().id());
        save(run("second-"+home,11,"LIVE"));
        var updated=recommendations.findActive(home);
        assertThat(updated).hasSize(1);
        assertThat(updated.getFirst().suggestedStart()).isEqualTo(Instant.parse("2026-09-30T03:00:00Z"));
        assertThat(recommendations.findById(initial.getFirst().id()).orElseThrow().status()).isEqualTo(RecommendationStatus.SUPERSEDED);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test void residentHttpReadsAndWritesRemainRestrictedToTheirOwnHousehold() throws Exception {
        save(run("security-"+home,10,"LIVE"));
        var owner=user("owner-"+home).roles("RESIDENT");
        mvc.perform(get("/api/v1/households/"+home+"/recommendations").with(owner)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/households/"+home+"/recommendations/generate").with(owner).with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/households/"+other+"/recommendations").with(owner)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/households/"+other+"/recommendations/generate").with(owner).with(csrf()))
                .andExpect(status().isForbidden());
        var otherRec=recommendations.findActive(other).getFirst();
        mvc.perform(post("/api/v1/recommendations/"+otherRec.id()+"/accept").with(owner).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(recommendations.findById(otherRec.id()).orElseThrow().status()).isEqualTo(RecommendationStatus.ACTIVE);
        var anonymousResponse=mvc.perform(get("/api/v1/households/"+home+"/recommendations")).andReturn().getResponse();
        assertThat(anonymousResponse.getStatus()).isBetween(300,499);
    }

    @Test void scheduledWorkerNeedsNoSessionAndNeverWritesSimulatedHouseholds() {
        save(run("scheduled-"+home,10,"LIVE"));
        job.every30Minutes();
        assertThat(recommendations.findRefresh(home)).isPresent();
        events.publishEvent(new HouseholdChanged(simulated));
        assertThat(recommendations.findRefresh(simulated)).isEmpty();
        assertThat(recommendations.findActive(simulated)).isEmpty();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test void automaticUpdatesPreserveAResidentsAcceptedRun() throws Exception {
        save(run("before-accept-"+home,10,"LIVE"));
        var suggestion=recommendations.findActive(home).getFirst();
        mvc.perform(post("/api/v1/recommendations/"+suggestion.id()+"/accept")
                .with(user("owner-"+home).roles("RESIDENT")).with(csrf())).andExpect(status().isOk());
        save(run("after-accept-"+home,11,"LIVE"));
        assertThat(recommendations.findById(suggestion.id()).orElseThrow().status()).isEqualTo(RecommendationStatus.ACCEPTED);
        assertThat(recommendations.findActive(home)).isEmpty();
    }
}

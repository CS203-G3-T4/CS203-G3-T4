package sg.edu.smu.cs203.forecast;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import sg.edu.smu.cs203.market.EnergyMarketServiceApplication;
import sg.edu.smu.cs203.market.price.*;
import sg.edu.smu.cs203.market.weather.DailyWeatherRepository;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static sg.edu.smu.cs203.forecast.ForecastMathTest.*;

@EnabledIfEnvironmentVariable(named="WATTLY_TEST_DB_URL",matches=".+")
@SpringBootTest(classes={EnergyMarketServiceApplication.class,ForecastPersistenceTest.Time.class},
        webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"market.feed.enabled=false","weather.daily.enabled=false","forecast.mode=REPLAY",
                    "forecast.period-mapping-evidence=SYNTHETIC_TEST_ONLY"})
class ForecastPersistenceTest {
    static final String SCHEMA="f3_test_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",() -> System.getenv("WATTLY_TEST_DB_URL"));
        p.add("spring.datasource.username",() -> System.getenv().getOrDefault("WATTLY_TEST_DB_USER","wattly_test"));
        p.add("spring.datasource.password",() -> System.getenv().getOrDefault("WATTLY_TEST_DB_PASSWORD",""));
        p.add("spring.flyway.default-schema",() -> SCHEMA);
        p.add("spring.datasource.hikari.connection-init-sql",() -> "SET search_path TO "+SCHEMA);
    }
    @TestConfiguration static class Time {
        @Bean @Primary Clock testClock() { return CLOCK; }
    }
    @Autowired MarketHistoryRepository history;
    @Autowired ForecastRepository saved;
    @Autowired ForecastJob job;
    @Autowired ForecastController controller;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired DailyWeatherRepository weather;
    @Autowired MarketHistoryImport importer;
    @MockitoBean PythonForecastClient python;
    @LocalServerPort int port;

    @Test
    void migrationsRevisionReadbackCompleteRunsClockReplayAndHttpToUi() throws Exception {
        for (MarketRevision r:rows()) history.save(r,"fixture",r.periodStart().toString(),"{}");
        MarketRevision same=rows().getLast();
        assertThat(history.save(same,"other-external-hash","different-hash","{}")).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from market_price_revision",Integer.class)).isEqualTo(384);
        MarketRevision delayed=new MarketRevision(2,same.source(),same.periodStart(),same.sourceUpdatedAt(),NOW.plusSeconds(60),
                BigDecimal.valueOf(9999),same.demand(),null,"PROVISIONAL","EMC_PERIOD");
        history.save(delayed,"fixture","delayed","{}");
        assertThat(history.asOf(NOW).getLast().usep()).isEqualByComparingTo(same.usep());
        assertThat(history.asOf(NOW.plusSeconds(60)).getLast().usep()).isEqualByComparingTo("9999");
        when(python.forecast(anyString(),any(),anyList())).thenThrow(new IllegalStateException("fixture offline"));
        job.runOnce(); job.runOnce();
        assertThat(jdbc.queryForObject("select count(*) from forecast_run",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from forecast_point",Integer.class)).isEqualTo(72);
        var run=saved.latest("REPLAY",NOW).orElseThrow();
        assertThat(run.points()).hasSize(24); assertThat(run.modelType()).isEqualTo("BASELINE");
        assertThat(saved.latest("LIVE",NOW)).isEmpty();
        assertThat(saved.latest("REPLAY",NOW.minusSeconds(1))).isEmpty();
        var partial=new ForecastTypes.Run("partial",NOW,NOW,"other","REPLAY","BASELINE","B1-v1","B1",null,
                List.of(),false,"AVAILABLE",run.points());
        assertThatThrownBy(() -> saved.save(partial,"[]",java.util.Map.of("B1",run.points().subList(0,23)),null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("select count(*) from forecast_run",Integer.class)).isEqualTo(1);
        // Incoming actuals create evaluations without altering any forecast point.
        for (var point:run.points()) history.save(row(point.targetPeriod(),BigDecimal.ZERO),"actual-fixture",point.targetPeriod().toString(),"{}");
        saved.evaluateAvailable(NOW.plus(Duration.ofDays(1)),"REPLAY");
        assertThat(saved.points(run.id(),"B1")).isEqualTo(run.points());
        assertThat(jdbc.queryForObject("select count(*) from forecast_evaluation",Integer.class)).isEqualTo(72);
        assertThat(saved.accuracy(7,NOW,"REPLAY").get("available")).isEqualTo(false);
        assertThat(saved.accuracy(7,NOW.plus(Duration.ofDays(1)),"REPLAY").get("available")).isEqualTo(true);
        var mvc=MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/api/v1/admin/forecast-accuracy")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/forecast-accuracy").principal(() -> "fixture-admin").with(req -> {req.addUserRole("ADMIN");return req;}))
                .andExpect(status().isOk());
        var http=HttpClient.newHttpClient();
        var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/forecast/latest")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(response.body()).path("run").path("points").size()).isEqualTo(24);
        var page=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/forecast.html")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(page.body()).contains("id=\"forecast-points\"","/js/forecast.js");
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/forecast-smoke-response.json"),response.body());
        if (System.getenv("WATTLY_UI_SMOKE")!=null) {
            var proof=java.nio.file.Path.of("target/forecast-ui-proof.json").toAbsolutePath();
            java.nio.file.Files.deleteIfExists(proof);
            var builder=new ProcessBuilder("node","--test","src/test/js/forecast-smoke.test.js").inheritIO();
            builder.environment().put("WATTLY_FORECAST_BASE_URL","http://127.0.0.1:"+port);
            builder.environment().put("WATTLY_UI_PROOF",proof.toString());
            var browser=builder.start();
            assertThat(browser.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(browser.exitValue()).isZero();
            assertThat(mapper.readTree(proof.toFile()).path("renderedRows").asInt()).isEqualTo(24);
        }
        var daily=mapper.readTree(getClass().getResourceAsStream("/weather/daily-forecast.json")).path("data").path("records");
        weather.save(daily,NOW); weather.save(daily,NOW.plusSeconds(30));
        assertThat(jdbc.queryForObject("select count(*) from daily_weather_revision",Integer.class)).isEqualTo(daily.size());
        doAnswer(call -> PythonForecastClientTest.syntheticResponse(call.getArgument(0))).when(python).forecast(anyString(),any(),anyList());
        job.runOnce();
        assertThat(saved.latest("REPLAY",NOW).orElseThrow().modelType()).isEqualTo("AI");
        assertThat(saved.latest("REPLAY",NOW).orElseThrow().points()).hasSize(24);
        assertThat(saved.baselineRanking(NOW,"REPLAY")).containsExactly("B2","B1","B3");
        assertThat(saved.baselineRanking(NOW,"LIVE")).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from model_run",Integer.class)).isEqualTo(1);
        var line=mapper.createObjectNode();
        Instant old=BaselineForecastService.floor(NOW).minus(Duration.ofDays(40));
        line.put("source","NEMS_SN_SG").put("periodStart",old.toString()).put("sourceUpdatedAt",old.plusSeconds(60).toString())
                .put("availableAt",old.plusSeconds(120).toString()).put("usep",new BigDecimal("-12.34567")).put("demand",6000)
                .put("priceStatus","PROVISIONAL").put("timeMapping","UNVERIFIED_FLOOR")
                .put("revisionId","fixture-external-hash").put("externalId","fixture-external-hash").put("provenance","fixture-import");
        var file=java.nio.file.Files.createTempFile("wattly-import-test-",".jsonl");
        try {
            int before=jdbc.queryForObject("select count(*) from market_price_revision",Integer.class);
            java.nio.file.Files.writeString(file,line+"\n{}\n");
            assertThatThrownBy(() -> importer.importFile(file)).isInstanceOf(IllegalArgumentException.class);
            assertThat(jdbc.queryForObject("select count(*) from market_price_revision",Integer.class)).isEqualTo(before);
            java.nio.file.Files.writeString(file,line+"\n");
            assertThat(importer.importFile(file)).isEqualTo(1);
            assertThat(importer.importFile(file)).isZero();
            assertThat(jdbc.queryForObject("select usep_sgd_per_mwh from market_price where source='NEMS_SN_SG'",BigDecimal.class))
                    .isEqualByComparingTo("-12.3457");
        } finally { java.nio.file.Files.deleteIfExists(file); }
    }
}

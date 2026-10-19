package sg.edu.smu.cs203.forecast;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import sg.edu.smu.cs203.market.EnergyMarketServiceApplication;
import sg.edu.smu.cs203.market.price.MarketHistoryRepository;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static sg.edu.smu.cs203.forecast.ForecastMathTest.*;

@EnabledIfEnvironmentVariable(named="WATTLY_TEST_DB_URL",matches=".+")
@SpringBootTest(classes={EnergyMarketServiceApplication.class,ForecastAcceptancePersistenceTest.Time.class},
    webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"market.feed.enabled=false","weather.daily.enabled=false","forecast.mode=REPLAY",
        "forecast.period-mapping-evidence=SYNTHETIC_TEST_ONLY","forecast.admin.username=fixture-admin",
        "forecast.admin.password=fixture-only-password","forecast.admin.local-basic-enabled=true","recommendation.forecast-source=f3"})
class ForecastAcceptancePersistenceTest {
    static final String SCHEMA="acceptance_test_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",()->System.getenv("WATTLY_TEST_DB_URL"));
        p.add("spring.datasource.username",()->System.getenv().getOrDefault("WATTLY_TEST_DB_USER","wattly_test"));
        p.add("spring.datasource.password",()->System.getenv().getOrDefault("WATTLY_TEST_DB_PASSWORD",""));
        p.add("spring.flyway.default-schema",()->SCHEMA);
        p.add("spring.datasource.hikari.connection-init-sql",()->"SET search_path TO "+SCHEMA);
    }
    @TestConfiguration static class Time { @Bean @Primary Clock testClock() { return CLOCK; } }
    @Autowired ForecastOperationsRepository operations;
    @Autowired ForecastRepository saved;
    @Autowired ForecastAccuracyService accuracy;
    @Autowired MarketHistoryRepository history;
    @Autowired ForecastJob job;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockitoBean PythonForecastClient python;
    @LocalServerPort int port;

    @Test void independentRankingDemandVintagesAttemptAuditAdminAuthorizationAndCommonCoverage() throws Exception {
        for (var row:rows()) history.save(row,"fixture",row.periodStart().toString(),"{}");
        var evidence=mapper.createObjectNode();
        evidence.put("id","SYNTHETIC_RANKING_TEST").put("mode","REPLAY").put("start",NOW.minus(Duration.ofDays(9)).toString())
            .put("end",NOW.minus(Duration.ofDays(1)).toString()).put("datasetDigest","a".repeat(64)).put("commonOriginDays",7).put("commonPairs",200);
        evidence.set("ranking",mapper.valueToTree(List.of("B2","B1","B3")));
        var table=evidence.putObject("table"); int error=1;
        for (String method:List.of("B2","B1","B3")) table.putObject(method).put("mae",error++).put("commonPairs",200);
        jdbc.execute("ALTER TABLE baseline_validation ADD COLUMN future_metadata TEXT DEFAULT 'future-default'");
        operations.saveRanking(evidence,NOW); operations.saveRanking(evidence,NOW);
        assertThat(jdbc.queryForObject("SELECT future_metadata FROM baseline_validation",String.class)).isEqualTo("future-default");
        assertThat(operations.ranking(NOW,"REPLAY")).containsExactly("B2","B1","B3");
        assertThat(operations.ranking(NOW.minusSeconds(1),"REPLAY")).isEmpty();
        assertThat(operations.ranking(NOW,"LIVE")).isEmpty();
        when(python.forecast(anyString(),any(),anyList())).thenThrow(new IllegalStateException("TEST_OUTAGE"));
        job.runOnce();
        assertThat(saved.latest("REPLAY",NOW).orElseThrow().selectedModel()).isEqualTo("B2");
        assertThat(saved.latest("REPLAY",NOW).orElseThrow().qualityFlags()).doesNotContain("BASELINE_RANKING_UNRANKED");
        var demand=new ForecastOperationsRepository.Demand(BaselineForecastService.targets(NOW).getFirst(),NOW.minusSeconds(60),NOW,BigDecimal.valueOf(7000),"SYNTHETIC_FORECAST");
        jdbc.execute("ALTER TABLE expected_demand_revision ADD COLUMN future_metadata TEXT DEFAULT 'future-default'");
        operations.saveDemand(List.of(demand),NOW); operations.saveDemand(List.of(demand),NOW);
        assertThat(jdbc.queryForObject("SELECT future_metadata FROM expected_demand_revision",String.class)).isEqualTo("future-default");
        assertThat(operations.demand(NOW)).containsExactly(demand);
        assertThat(operations.demand(NOW.minusSeconds(1))).isEmpty();
        assertThatThrownBy(()->operations.saveDemand(List.of(new ForecastOperationsRepository.Demand(demand.targetPeriod(),NOW,NOW.plusSeconds(1),BigDecimal.ONE,"SYNTHETIC")),NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->operations.saveDemand(List.of(new ForecastOperationsRepository.Demand(demand.targetPeriod(),demand.publishedAt(),NOW,BigDecimal.ONE,demand.source())),NOW)).isInstanceOf(IllegalArgumentException.class);

        var http=HttpClient.newHttpClient();
        assertThat(send(http,"GET","/api/v1/admin/forecast-accuracy",null,false).statusCode()).isEqualTo(403);
        assertThat(send(http,"GET","/admin-forecast.html",null,false).statusCode()).isEqualTo(401);
        assertThat(send(http,"GET","/admin-forecast.html",null,true).statusCode()).isEqualTo(200);
        assertThat(send(http,"GET","/api/v1/admin/forecast-accuracy",null,true).statusCode()).isEqualTo(200);
        for (String state:List.of("SUCCESS","REJECTED","FAILED")) {
            ObjectNode attempt=mapper.createObjectNode();
            attempt.put("attemptId","TEST_"+state).put("modelVersion","TEST_CANDIDATE").put("trainingDate",NOW.toString())
                .put("trainingStart",NOW.minus(Duration.ofDays(400)).toString()).put("trainingEnd",NOW.minus(Duration.ofDays(28)).toString())
                .put("datasetDigest","b".repeat(64)).put("state","RUNNING").put("promotionDecision","NOT_ATTEMPTED").put("reason","");
            attempt.set("scores",mapper.valueToTree(Map.of("mae",123)));
            assertThat(send(http,"POST","/api/v1/admin/forecast-training-attempts",attempt.toString(),true).statusCode()).isEqualTo(200);
            attempt.put("state",state).put("promotionDecision",state.equals("SUCCESS")?"PROMOTED":"RETAINED").put("reason","Synthetic lifecycle test");
            assertThat(send(http,"POST","/api/v1/admin/forecast-training-attempts",attempt.toString(),true).statusCode()).isEqualTo(200);
            assertThat(send(http,"POST","/api/v1/admin/forecast-training-attempts",attempt.toString(),true).statusCode()).isEqualTo(200);
            attempt.put("reason","Cannot rewrite completed attempt");
            assertThat(send(http,"POST","/api/v1/admin/forecast-training-attempts",attempt.toString(),true).statusCode()).isEqualTo(400);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM model_run WHERE outcome IN ('SUCCESS','REJECTED','FAILED')",Integer.class)).isEqualTo(3);
        var audit=send(http,"GET","/api/v1/admin/forecast-training-attempts?limit=3",null,true);
        assertThat(audit.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(audit.body()).path("attempts").size()).isEqualTo(3);
        assertThat(send(http,"GET","/api/v1/admin/forecast-training-attempts",null,false).statusCode()).isEqualTo(403);
        assertThat(send(http,"GET","/api/v1/admin/forecast-training-attempts?limit=0",null,true).statusCode()).isEqualTo(400);
        // All methods share the same saved origin; missing actuals must not become zeros or a fair-ranking claim.
        Instant origin=NOW.minus(Duration.ofDays(1));
        var points=BaselineForecastService.targets(origin).stream().map(t->new ForecastTypes.Point(
            BaselineForecastService.targets(origin).indexOf(t)+1,t,BigDecimal.TEN,null,null)).toList();
        var run=new ForecastTypes.Run("TEST_PAST",origin,origin,"TEST_PAST","REPLAY","BASELINE","B1-v1","B1",null,List.of(),false,"AVAILABLE",points);
        saved.save(run,"[]",Map.of("B1",points,"B2",points,"B3",points),null);
        saved.evaluateAvailable(NOW,"REPLAY");
        var scored=accuracy.read(7,1,"PROVISIONAL_FIRST_EVALUATION",NOW,"REPLAY");
        assertThat(scored.get("commonPairs")).isEqualTo(1);
        if ("true".equals(System.getenv("WATTLY_BROWSER_SMOKE"))) {
            var browser=new ProcessBuilder("node","scripts/forecast-pages-browser.cjs","http://127.0.0.1:"+port,
                    "target/forecast-admin-fixture-browser");
            browser.environment().putAll(Map.of("WATTLY_ADMIN_USER","fixture-admin","WATTLY_ADMIN_PASSWORD","fixture-only-password",
                    "WATTLY_ADMIN_EXPECT_SERIES","true","WATTLY_BROWSER_PROOF_SOURCE","SYNTHETIC_PERSISTENCE_REGRESSION_ONLY"));
            browser.redirectErrorStream(true).redirectOutput(java.nio.file.Path.of("target/forecast-admin-fixture-browser.log").toFile());
            var process=browser.start();
            assertThat(process.waitFor(90,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).as("Populated ADMIN chart in real Chromium").isZero();
        }
        jdbc.update("DELETE FROM forecast_evaluation WHERE run_id='TEST_PAST' AND model_identifier='B3'");
        assertThat(accuracy.read(7,1,"PROVISIONAL_FIRST_EVALUATION",NOW,"REPLAY").get("commonPairs")).isEqualTo(0);
        assertThatThrownBy(()->accuracy.read(7,25,"FINAL",NOW,"REPLAY")).isInstanceOf(IllegalArgumentException.class);
    }
    private HttpResponse<String> send(HttpClient http,String method,String path,String body,boolean admin) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
        if(admin) request.header("Authorization","Basic "+Base64.getEncoder().encodeToString("fixture-admin:fixture-only-password".getBytes(StandardCharsets.UTF_8)));
        if(body!=null) {
            request.header("Content-Type","application/json");
            if(admin) {
                var csrfRequest=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/csrf")).GET().build();
                var csrfResponse=http.send(csrfRequest,HttpResponse.BodyHandlers.ofString());
                assertThat(csrfResponse.statusCode()).isEqualTo(200);
                var csrf=mapper.readTree(csrfResponse.body());
                request.header("Cookie",csrfResponse.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0])
                        .header(csrf.path("headerName").asText(),csrf.path("token").asText());
            }
            request.POST(HttpRequest.BodyPublishers.ofString(body));
        } else request.GET();
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
}

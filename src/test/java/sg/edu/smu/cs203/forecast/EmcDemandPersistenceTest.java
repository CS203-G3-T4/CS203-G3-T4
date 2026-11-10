package sg.edu.smu.cs203.forecast;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import sg.edu.smu.cs203.market.EnergyMarketServiceApplication;
import sg.edu.smu.cs203.market.price.MarketHistoryRepository;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="WATTLY_TEST_DB_URL",matches=".+")
@SpringBootTest(classes={EnergyMarketServiceApplication.class,EmcDemandPersistenceTest.Time.class},properties={
 "market.feed.enabled=false","weather.daily.enabled=false","forecast.mode=REPLAY","forecast.import-file=",
 "forecast.period-mapping-evidence=SYNTHETIC_TEST_ONLY","recommendation.forecast-source=f3"})
class EmcDemandPersistenceTest {
 static final String SCHEMA="emc_demand_"+UUID.randomUUID().toString().replace("-","");
 @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
  p.add("spring.datasource.url",()->System.getenv("WATTLY_TEST_DB_URL"));
  p.add("spring.datasource.username",()->System.getenv().getOrDefault("WATTLY_TEST_DB_USER","wattly_test"));
  p.add("spring.datasource.password",()->System.getenv().getOrDefault("WATTLY_TEST_DB_PASSWORD",""));
  p.add("spring.flyway.default-schema",()->SCHEMA);p.add("spring.datasource.hikari.connection-init-sql",()->"SET search_path TO "+SCHEMA);
 }
 @TestConfiguration static class Time {@Bean @Primary Clock testClock(){return ForecastMathTest.CLOCK;}}
 @Autowired ForecastOperationsRepository operations;
 @Autowired JdbcTemplate jdbc;
 @Autowired ForecastJob forecasts;
 @Autowired MarketHistoryRepository history;
 @MockitoBean PythonForecastClient python;
 @Test void firstReceiptIsRetainedConflictsRollbackAndIssuedDemandReachesPython() {
  Instant now=ForecastMathTest.NOW;
  var rows=EmcDemandSnapshotImporter.parse(EmcDemandSnapshotTest.snapshot(now),now);
  assertThat(operations.saveNewDemand(rows,now)).isEqualTo(24);
  var repeated=rows.stream().map(row->new ForecastOperationsRepository.Demand(row.targetPeriod(),row.publishedAt(),row.availableAt().plusSeconds(1),row.demandMw(),row.source())).toList();
  assertThat(operations.saveNewDemand(repeated,now)).isZero();
  assertThat(operations.demand(now)).hasSize(24).allSatisfy(row->assertThat(row.availableAt()).isEqualTo(now.minusSeconds(1)));
  assertThat(operations.demand(now.minusSeconds(2))).isEmpty();
  var original=rows.getFirst();var newRow=new ForecastOperationsRepository.Demand(original.targetPeriod(),now,now,BigDecimal.valueOf(6555),original.source());
  var conflict=new ForecastOperationsRepository.Demand(original.targetPeriod(),original.publishedAt(),original.availableAt(),BigDecimal.ONE,original.source());
  assertThatThrownBy(()->operations.saveNewDemand(List.of(newRow,conflict),now)).isInstanceOf(IllegalArgumentException.class);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM expected_demand_revision",Integer.class)).isEqualTo(24);
  for(var row:ForecastMathTest.rows())history.save(row,"fixture",row.periodStart().toString(),"{}");
  when(python.forecast(anyString(),any(),anyList(),anyList())).thenThrow(new IllegalStateException("TEST_DOWN"));
  forecasts.runOnce();
  verify(python).forecast(anyString(),eq(now),anyList(),argThat(ds->ds.size()==24 && ds.getFirst().availableAt().equals(now.minusSeconds(1))));
 }
}

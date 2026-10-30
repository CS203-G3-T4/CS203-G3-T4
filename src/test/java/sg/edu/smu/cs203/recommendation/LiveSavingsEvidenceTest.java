package sg.edu.smu.cs203.recommendation;

import java.math.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.*;
import sg.edu.smu.cs203.forecast.*;
import sg.edu.smu.cs203.household.*;
import sg.edu.smu.cs203.household.appliance.*;
import sg.edu.smu.cs203.market.EnergyMarketServiceApplication;
import sg.edu.smu.cs203.market.price.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Every synthetic sample lives in an isolated test schema, never in the actual live trial. */
@EnabledIfEnvironmentVariable(named="WATTLY_TEST_DB_URL",matches=".+")
@SpringBootTest(classes={EnergyMarketServiceApplication.class,LiveSavingsEvidenceTest.Time.class},properties={
 "market.feed.enabled=false","weather.daily.enabled=false","forecast.mode=LIVE","forecast.import-file=",
 "forecast.period-mapping-evidence=SYNTHETIC_TEST_ONLY","recommendation.forecast-source=f3",
 "recommendation.auto-recompute=true","recommendation.recompute-cron=0 0 0 1 1 *",
 "forecast.live-comparison.enabled=true","forecast.live-comparison.id=TEST_ONLY_TRIAL",
 "forecast.live-comparison.model-version=SOLAR_TEST_FROZEN",
 "forecast.live-comparison.model-sha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
 "forecast.live-comparison.cron=0 0 0 1 1 *"})
@AutoConfigureMockMvc
class LiveSavingsEvidenceTest {
 static final Instant BASE=Instant.parse("2026-10-01T00:10:00Z");
 static final String HASH="a".repeat(64),MODEL="SOLAR_TEST_FROZEN";
 static final String SCHEMA="live_evidence_"+UUID.randomUUID().toString().replace("-","");
 @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
  p.add("spring.datasource.url",()->System.getenv("WATTLY_TEST_DB_URL"));
  p.add("spring.datasource.username",()->System.getenv().getOrDefault("WATTLY_TEST_DB_USER","wattly_test"));
  p.add("spring.datasource.password",()->System.getenv().getOrDefault("WATTLY_TEST_DB_PASSWORD",""));
  p.add("spring.flyway.default-schema",()->SCHEMA);p.add("spring.datasource.hikari.connection-init-sql",()->"SET search_path TO "+SCHEMA);
 }
 static class MovingClock extends Clock {
  Instant value=BASE;public ZoneId getZone(){return ZoneId.of("Asia/Singapore");}
  public Clock withZone(ZoneId z){return this;}public Instant instant(){return value;}
 }
 @TestConfiguration static class Time {@Bean @Primary MovingClock testClock(){return new MovingClock();}}
 @Autowired MovingClock clock;
 @Autowired JdbcTemplate jdbc;
 @Autowired ObjectMapper mapper;
 @Autowired HouseholdRepository households;
 @Autowired ApplianceRepository appliances;
 @Autowired MarketHistoryRepository history;
 @Autowired MarketPriceRepository currentPrices;
 @Autowired IngestionRunRepository ingestion;
 @Autowired ForecastRepository forecasts;
 @Autowired RecommendationRepository recommendations;
 @Autowired LiveComparisonService comparison;
 @Autowired LiveEvidenceRepository evidence;
 @Autowired SavingsMonitoringService monitoring;
 @Autowired RecommendationService service;
 @Autowired MockMvc mvc;
 long home,appliance,other;
 @BeforeEach void setup() {
  SecurityContextHolder.clearContext();clock.value=BASE;
  // Keep cases independent while preserving the frozen trial's activation time and policy.
  jdbc.update("DELETE FROM savings_change_alert");jdbc.update("DELETE FROM recommendation_savings_evidence");
  jdbc.update("DELETE FROM live_forecast_price");jdbc.update("DELETE FROM live_savings_opportunity");jdbc.update("DELETE FROM live_forecast_issue");
  jdbc.update("DELETE FROM forecast_evaluation");jdbc.update("DELETE FROM forecast_point");jdbc.update("DELETE FROM forecast_run");
  jdbc.update("DELETE FROM recommendation_event");jdbc.update("DELETE FROM recommendation");jdbc.update("DELETE FROM recommendation_refresh");
  jdbc.update("DELETE FROM market_price");jdbc.update("DELETE FROM market_revision_origin");jdbc.update("DELETE FROM market_price_revision");jdbc.update("DELETE FROM ingestion_run");
  home=home("Trial home");other=home("Other trial home");
  appliance=appliances.insert(new Appliance(null,home,"Test dryer",ApplianceType.TUMBLE_DRYER,BigDecimal.ONE,30,Flexibility.FLEXIBLE,
   LocalTime.of(9,0),LocalTime.of(20,30),LocalTime.of(19,0),7,true,null,BASE,BASE));
  jdbc.update("INSERT INTO user_account(username,password_hash,role,household_id) VALUES(?,?,?,?)","owner-"+home,"unused-test-hash","RESIDENT",home);
  freshPrice();
 }
 long home(String name){return households.insert(new Household(null,name,DwellingType.HDB_4_ROOM,4,PlanType.PRICE_LINKED,null,null,false,BASE,BASE));}
 void freshPrice(){var t=BaselineForecastService.floor(clock.instant());
  currentPrices.upsert(new MarketPrice("NEMS_SN_SG",t,t.plusSeconds(60),clock.instant(),BigDecimal.valueOf(200),BigDecimal.valueOf(6000),BigDecimal.valueOf(225)));
  history.save(new MarketRevision(0,"NEMS_SN_SG",t,t.plusSeconds(60),clock.instant(),BigDecimal.valueOf(200),null,null,"PROVISIONAL","EMC_PERIOD"),"TEST",t.toString(),"{}");
  ingestion.save(new IngestionRun(clock.instant(),clock.instant(),IngestionStatus.SUCCESS,t.plusSeconds(60),null));
 }
 List<ForecastTypes.Point> points(Instant now,int cheapHour,boolean changed){return IntStream.rangeClosed(1,24).mapToObj(h->{
  Instant t=BaselineForecastService.targets(now).get(h-1);int hour=t.atZone(clock.getZone()).getHour(),minute=t.atZone(clock.getZone()).getMinute();
  BigDecimal price=BigDecimal.valueOf(hour==cheapHour&&minute==0?50:300);
  if(changed&&hour==10&&minute==0)price=BigDecimal.valueOf(400);
  return new ForecastTypes.Point(h,t,price,BigDecimal.valueOf(350),price.compareTo(BigDecimal.valueOf(350))>0);
 }).toList();}
 String save(String id,int cheapHour,boolean changed){Instant now=clock.instant();var ai=points(now,cheapHour,changed);var b3=points(now,12,false);
  var run=new ForecastTypes.Run(id,now,now,id,"LIVE","AI",MODEL,"AI",null,List.of(),false,"AVAILABLE",ai);
  var manifest=mapper.createObjectNode().put("version",MODEL).put("modelSha256",HASH).put("usableFrom",BASE.minusSeconds(3600).toString()).put("productionEligible",false);
  forecasts.save(run,"[]",Map.of("AI",ai,"B3",b3),manifest);return id;
 }
 @Test void matchedPlansWaitForMaturityUseIdenticalActualsAndNeverRewriteTruth() {
  String id=save("pair",10,false);
  assertThat(evidence.opportunities(comparison.trialId())).anyMatch(x->x.path("household_id").asLong()==home);
  comparison.evaluate();assertThat(comparison.report().get("scoredOpportunities")).isEqualTo(0);
  clock.value=BASE.plus(Duration.ofHours(13));
  for(var point:forecasts.points(id,"AI")) {
   int hour=point.targetPeriod().atZone(clock.getZone()).getHour();BigDecimal actual=BigDecimal.valueOf(hour==10?400:hour==12?50:100);
   history.save(new MarketRevision(0,"NEMS_SN_SG",point.targetPeriod(),point.targetPeriod().plusSeconds(60),point.targetPeriod().plusSeconds(61),actual,null,null,"PROVISIONAL","EMC_PERIOD"),
    "TRUTH",point.targetPeriod().toString(),"{}");
  }
  comparison.evaluate();
  var row=evidence.opportunities(comparison.trialId()).stream().filter(x->x.path("household_id").asLong()==home).findFirst().orElseThrow();
  assertThat(row.path("outcome").path("AI").path("savingSgd").decimalValue()).isNegative();
  assertThat(row.path("outcome").path("B3").path("savingSgd").decimalValue()).isPositive();
  String frozen=row.path("outcome").toString();
  var ten=BASE.atZone(clock.getZone()).toLocalDate().atTime(10,0).atZone(clock.getZone()).toInstant();
  history.save(new MarketRevision(0,"NEMS_SN_SG",ten,clock.instant().minusSeconds(2),clock.instant(),BigDecimal.ONE,null,null,"PROVISIONAL","EMC_PERIOD"),"REVISION","late","{}");
  comparison.evaluate();
  assertThat(evidence.opportunities(comparison.trialId()).stream().filter(x->x.path("id").asLong()==row.path("id").asLong()).findFirst().orElseThrow().path("outcome").toString()).isEqualTo(frozen);
  assertThat(evidence.scoredPrices(comparison.trialId())).hasSize(24);
 }
 @Test void lateAvailabilityDoesNotLeakIntoOutcomeOrCalibration() {
  String id=save("late",10,false);clock.value=BASE.plus(Duration.ofHours(13));
  for(var point:forecasts.points(id,"AI"))history.save(new MarketRevision(0,"NEMS_SN_SG",point.targetPeriod(),point.targetPeriod().plusSeconds(60),clock.instant().plus(Duration.ofDays(1)),
   BigDecimal.valueOf(100),null,null,"PROVISIONAL","EMC_PERIOD"),"LATE",point.targetPeriod().toString(),"{}");
  comparison.evaluate();assertThat(evidence.scoredPrices(comparison.trialId())).isEmpty();
  assertThat(evidence.calibration(comparison.trialId(),"AI","SHORT:12H",clock.instant())).isEmpty();
  assertThat(comparison.report().get("scoredOpportunities")).isEqualTo(0);
 }
 @Test void finalAndForeignRevisionsCannotHideAvailableProvisionalTrialTruth() {
  String id=save("mixed-truth",10,false);clock.value=BASE.plus(Duration.ofHours(13));
  for(var point:forecasts.points(id,"AI")) {
   Instant target=point.targetPeriod();
   saveTruth(target,"NEMS_SN_SG","PROVISIONAL",100,target.plusSeconds(60),target.plusSeconds(61),"earlier");
   saveTruth(target,"NEMS_SN_SG","PROVISIONAL",125,target.plusSeconds(120),target.plusSeconds(121),"latest-eligible");
   saveTruth(target,"NEMS_SN_SG","FINAL",900,target.plusSeconds(180),target.plusSeconds(181),"final");
   saveTruth(target,"OTHER_PROVIDER","PROVISIONAL",999,target.plusSeconds(240),target.plusSeconds(241),"foreign");
   saveTruth(target,"NEMS_SN_SG","PROVISIONAL",888,clock.instant().plusSeconds(60),clock.instant().plusSeconds(61),"future-publication");
   saveTruth(target,"NEMS_SN_SG","PROVISIONAL",777,target.plusSeconds(300),clock.instant().plusSeconds(60),"future-receipt");
  }
  comparison.evaluate();
  assertThat(evidence.scoredPrices(comparison.trialId())).hasSize(24).allSatisfy(row ->
   assertThat(row.path("actual").decimalValue()).isEqualByComparingTo("125"));
  var scored=evidence.opportunities(comparison.trialId()).stream().filter(row->row.path("household_id").asLong()==home).findFirst().orElseThrow();
  assertThat(scored.path("outcome").path("AI").path("savingSgd").decimalValue()).isZero();
  assertThat(scored.path("outcome").path("B3").path("savingSgd").decimalValue()).isZero();
  for(var revision:scored.path("outcome").path("truthRevisions")) {
   var status=jdbc.queryForMap("SELECT source,price_status,usep FROM market_price_revision WHERE id=?",revision.path("revision").asLong());
   assertThat(status.get("source")).isEqualTo("NEMS_SN_SG");assertThat(status.get("price_status")).isEqualTo("PROVISIONAL");
   assertThat((BigDecimal)status.get("usep")).isEqualByComparingTo("125");
  }
 }
 void saveTruth(Instant target,String source,String status,int price,Instant published,Instant received,String identity) {
  history.save(new MarketRevision(0,source,target,published,received,BigDecimal.valueOf(price),null,null,status,"EMC_PERIOD"),
   "MIXED_TRUTH_TEST",identity+target,"{}");
 }
 @Test void finalAndForeignPricesAloneDoNotBecomeProvisionalTruth() {
  String id=save("no-provisional",10,false);clock.value=BASE.plus(Duration.ofHours(13));
  for(var point:forecasts.points(id,"AI")) {
   Instant target=point.targetPeriod();
   saveTruth(target,"NEMS_SN_SG","FINAL",900,target.plusSeconds(180),target.plusSeconds(181),"final-only");
   saveTruth(target,"OTHER_PROVIDER","PROVISIONAL",999,target.plusSeconds(240),target.plusSeconds(241),"foreign-only");
  }
  comparison.evaluate();
  assertThat(evidence.scoredPrices(comparison.trialId())).isEmpty();
  assertThat(comparison.report().get("scoredOpportunities")).isEqualTo(0);
 }
 void calibration(){
  String run="SYNTHETIC_CALIBRATION_ONLY";
  jdbc.update("INSERT INTO forecast_run(id,as_of,generated_at,origin_slot,input_revision,input_snapshot,mode,model_type,model_version,selected_model,quality_flags,stale,status) VALUES(?,?,?,?,?,'[]'::jsonb,'LIVE','AI',?,'AI','[]'::jsonb,false,'AVAILABLE')",
   run,Timestamp.from(BASE),Timestamp.from(BASE),Timestamp.from(BaselineForecastService.floor(BASE)),run,MODEL);
  evidence.issue(comparison.trialId(),run,BASE,true,null);
  for(int d=0;d<20;d++) {
   Instant issued=BASE.plus(Duration.ofDays(d)),usual=issued.plus(Duration.ofHours(11)),chosen=issued.plus(Duration.ofHours(2));
   var prediction=mapper.createObjectNode().put("AI",new BigDecimal(".10")).put("B3",new BigDecimal(".10"));
   evidence.opportunity(comparison.trialId(),run,home,appliance,issued,usual,chosen,chosen,BigDecimal.ONE,30,BigDecimal.ONE,"SHORT:12H",prediction);
  }
  for(var row:evidence.opportunities(comparison.trialId())) {
   var outcome=mapper.createObjectNode();for(String m:List.of("AI","B3"))outcome.putObject(m).put("savingSgd",new BigDecimal(".12")).put("errorPerKwh",new BigDecimal(".04"));
   evidence.outcome(row.path("id").asLong(),outcome,LiveEvidenceRepository.time(row,"issued_at").plus(Duration.ofHours(13)));
  }
  clock.value=BASE.plus(Duration.ofDays(21));freshPrice();
 }
 @Test void calibratedAcceptedStartAlertsAreQuietInsideRangeDeduplicatedAndHouseholdProtected() throws Exception {
  calibration();save("original",10,false);
  var rec=recommendations.findActive(home).getFirst();
  assertThat(evidence.evidence(rec.id()).path("proposal").path("range").path("available").asBoolean()).isTrue();
  mvc.perform(post("/api/v1/recommendations/"+rec.id()+"/accept").with(user("owner-"+home).roles("RESIDENT")).with(csrf())).andExpect(status().isOk());
  clock.value=BASE.plus(Duration.ofDays(21)).plus(Duration.ofMinutes(82));freshPrice();save("inside",10,false);monitoring.recheck();
  assertThat(evidence.alerts(home,clock.instant())).isEmpty();
  save("outside",10,true);monitoring.recheck();monitoring.recheck();
  assertThat(evidence.alerts(home,clock.instant())).hasSize(1);var alert=evidence.alerts(home,clock.instant()).getFirst();
  assertThat(alert.path("direction").asText()).isEqualTo("LOWER");
  clock.value=BASE.plus(Duration.ofDays(21)).plus(Duration.ofMinutes(112));freshPrice();save("at-start",11,true);monitoring.recheck();
  var current=evidence.evidence(rec.id()).path("latest_check");
  assertThat(current.path("estimatedSavingSgd").decimalValue()).isEqualByComparingTo(".0500");
  assertThat(current.path("observedRevisions")).anyMatch(x->x.path("periodStart").asText().equals(BaselineForecastService.floor(clock.instant()).toString()));
  assertThat(evidence.alerts(home,clock.instant())).hasSize(1);
  mvc.perform(get("/api/v1/households/"+other+"/recommendation-alerts").with(user("owner-"+home).roles("RESIDENT"))).andExpect(status().isForbidden());
  mvc.perform(post("/api/v1/households/"+other+"/recommendation-alerts/"+alert.path("id").asLong()+"/acknowledge").with(user("owner-"+home).roles("RESIDENT")).with(csrf())).andExpect(status().isForbidden());
  mvc.perform(post("/api/v1/households/"+home+"/recommendation-alerts/"+alert.path("id").asLong()+"/acknowledge").with(user("owner-"+home).roles("RESIDENT")).with(csrf())).andExpect(status().isOk());
  monitoring.recheck();assertThat(evidence.alerts(home,clock.instant())).isEmpty();
  // Acknowledgement stays quiet until a genuine return inside and new crossing.
  evidence.resolveAlerts(rec.id(),null,clock.instant());
  monitoring.recheck();assertThat(evidence.alerts(home,clock.instant())).hasSize(1);
 }
 @Test void collectingPhaseNeverInventsBoundsOrSendsSavingsChangeAlerts() throws Exception {
  save("cold",10,false);var rec=recommendations.findActive(home).getFirst();
  assertThat(evidence.evidence(rec.id()).path("proposal").path("range").path("available").asBoolean()).isFalse();
  mvc.perform(post("/api/v1/recommendations/"+rec.id()+"/accept").with(user("owner-"+home).roles("RESIDENT")).with(csrf())).andExpect(status().isOk());
  clock.value=BASE.plus(Duration.ofMinutes(82));freshPrice();save("cold-change",10,true);monitoring.recheck();
  assertThat(evidence.alerts(home,clock.instant())).isEmpty();
  assertThat(evidence.evidence(rec.id()).path("latest_check").path("status").asText()).isEqualTo("COLLECTING_LIVE_RESULTS");
 }
 @Test void startCheckStillUsesObservedProvisionalPriceWhenOtherRevisionsAreNewer() throws Exception {
  calibration();save("mixed-start-original",10,false);var rec=recommendations.findActive(home).getFirst();
  mvc.perform(post("/api/v1/recommendations/"+rec.id()+"/accept").with(user("owner-"+home).roles("RESIDENT")).with(csrf()))
   .andExpect(status().isOk());
  clock.value=BASE.plus(Duration.ofDays(21)).plus(Duration.ofMinutes(112));freshPrice();
  Instant current=BaselineForecastService.floor(clock.instant());
  saveTruth(current,"NEMS_SN_SG","FINAL",900,clock.instant().minusSeconds(2),clock.instant().minusSeconds(1),"start-final");
  saveTruth(current,"OTHER_PROVIDER","PROVISIONAL",999,clock.instant().minusSeconds(1),clock.instant(),"start-foreign");
  save("mixed-at-start",11,true);monitoring.recheck();
  var check=evidence.evidence(rec.id()).path("latest_check");
  assertThat(check.path("estimatedSavingSgd").decimalValue()).isEqualByComparingTo(".0500");
  assertThat(check.path("status").asText()).isEqualTo("OUTSIDE_PAST_ERROR_RANGE");
  assertThat(evidence.alerts(home,clock.instant())).hasSize(1);
 }
 @Test void trialModelIsPinnedFallbacksAreNotScoredAsAiAndRestartDoesNotResetWindow() {
  var start=evidence.trial(comparison.trialId()).path("started_at").asText();
  clock.value=BASE.plus(Duration.ofDays(1));comparison.initialize();
  assertThat(evidence.trial(comparison.trialId()).path("started_at").asText()).isEqualTo(start);
  assertThatThrownBy(()->evidence.trial(comparison.trialId(),"changed-model",HASH,comparison.policy(),clock.instant(),28))
      .isInstanceOf(IllegalStateException.class);
  var b3=points(clock.instant(),12,false);
  var fallback=new ForecastTypes.Run("fallback",clock.instant(),clock.instant(),"fallback","LIVE","BASELINE","B3-v1","B3",null,List.of(),false,"AVAILABLE",b3);
  forecasts.save(fallback,"[]",Map.of("B3",b3),null);
  assertThat(evidence.scoredPrices(comparison.trialId())).isEmpty();
  assertThat(evidence.opportunities(comparison.trialId())).isEmpty();
  assertThat(evidence.exclusions(comparison.trialId())).anyMatch(x->"AI_UNAVAILABLE_BASELINE_FALLBACK".equals(x.get("reason")));
 }
 @Test void uncalibratedOrStaleForecastsAndCustomAcceptedStartsDoNotProduceFalseNotices() throws Exception {
  calibration();save("custom-original",10,false);var rec=recommendations.findActive(home).getFirst();
  mvc.perform(post("/api/v1/recommendations/"+rec.id()+"/accept").contentType("application/json").content("{\"startTime\":\"11:00\"}")
    .with(user("owner-"+home).roles("RESIDENT")).with(csrf())).andExpect(status().isOk());
  assertThat(evidence.evidence(rec.id()).path("acceptance").path("estimatedSavingSgd").decimalValue()).isEqualByComparingTo("0");
  clock.value=BASE.plus(Duration.ofDays(21)).plus(Duration.ofHours(2));
  // There is no fresh saved forecast covering this clock, so even a calibrated plan stays quiet.
  monitoring.recheck();assertThat(evidence.alerts(home,clock.instant())).isEmpty();
  assertThat(evidence.evidence(rec.id()).path("latest_check").path("status").asText()).isEqualTo("NO_MATCHING_FRESH_FORECAST");
  mvc.perform(get("/api/v1/admin/live-forecast-comparison").with(user("owner-"+home).roles("RESIDENT"))).andExpect(status().isForbidden());
  mvc.perform(get("/admin-live-comparison.html").with(user("owner-"+home).roles("RESIDENT"))).andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/admin/live-forecast-comparison").with(user("admin-test").roles("ADMIN"))).andExpect(status().isOk());
 }

}

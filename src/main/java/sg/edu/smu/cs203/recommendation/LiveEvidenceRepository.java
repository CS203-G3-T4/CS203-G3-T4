package sg.edu.smu.cs203.recommendation;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import sg.edu.smu.cs203.forecast.ForecastTypes;
import sg.edu.smu.cs203.market.price.MarketRevision;

@Repository
public class LiveEvidenceRepository {
    final JdbcTemplate jdbc;
    final ObjectMapper mapper;
    public LiveEvidenceRepository(JdbcTemplate jdbc,ObjectMapper mapper) { this.jdbc=jdbc;this.mapper=mapper; }
    static Timestamp ts(Instant t) { return Timestamp.from(t); }
    List<JsonNode> rows(String sql,Object... args) {
        return jdbc.query(sql,(rs,n)->mapper.readTree(rs.getString(1)),args);
    }
    JsonNode trial(String id,String model,String sha,JsonNode policy,Instant now,int days) {
        jdbc.update("INSERT INTO live_forecast_trial(id,started_at,ends_at,model_version,model_sha256,policy) VALUES(?,?,?,?,?,?::jsonb) ON CONFLICT(id) DO NOTHING",
                id,ts(now),ts(now.plus(Duration.ofDays(days))),model,sha,policy.toString());
        var trial=rows("SELECT to_jsonb(t)::text FROM live_forecast_trial t WHERE id=?",id).getFirst();
        if (!model.equals(trial.path("model_version").asText()) || !sha.equals(trial.path("model_sha256").asText()) || !mapper.readTree(policy.toString()).equals(trial.path("policy")))
            throw new IllegalStateException("Frozen live trial policy/model changed; a reviewed new trial is required");
        return trial;
    }
    JsonNode trial(String id) { return rows("SELECT to_jsonb(t)::text FROM live_forecast_trial t WHERE id=?",id).stream().findFirst().orElse(null); }
    ForecastTypes.Run run(String id,sg.edu.smu.cs203.forecast.ForecastRepository forecasts) {
        var list=rows("SELECT to_jsonb(f)::text FROM forecast_run f WHERE id=? AND is_demo=FALSE",id);
        if (list.isEmpty()) return null;
        var f=list.getFirst();
        return new ForecastTypes.Run(id,time(f,"as_of"),time(f,"generated_at"),f.path("input_revision").asText(),f.path("mode").asText(),
            f.path("model_type").asText(),f.path("model_version").asText(),f.path("selected_model").asText(),null,List.of(mapper.convertValue(f.path("quality_flags"),String[].class)),f.path("stale").asBoolean(),
            f.path("status").asText(),forecasts.points(id,f.path("selected_model").asText()));
    }
    boolean demoRun(String id) {
        return jdbc.query("SELECT is_demo FROM forecast_run WHERE id=?",(rs,n)->rs.getBoolean(1),id)
                .stream().findFirst().orElse(false);
    }
    void captureExclusion(String run,Long household,Long appliance,String reason,Instant now) {
        jdbc.update("INSERT INTO live_capture_exclusion(run_id,household_id,appliance_id,reason,recorded_at) VALUES(?,?,?,?,?)",
                run,household,appliance,reason,ts(now));
    }
    List<Map<String,Object>> captureExclusions(String trial) {
        return jdbc.queryForList("""
            SELECT e.reason,count(*) AS count,count(DISTINCT e.run_id) AS affected_runs
            FROM live_capture_exclusion e JOIN live_forecast_issue i ON i.run_id=e.run_id
            WHERE i.trial_id=? GROUP BY e.reason ORDER BY e.reason
            """,trial);
    }
    String modelHash(String version) {
        return jdbc.query("SELECT manifest->>'modelSha256' FROM model_run WHERE version=?",(rs,n)->rs.getString(1),version).stream().findFirst().orElse("");
    }
    boolean issue(String trial,String run,Instant now,boolean eligible,String reason) {
        return jdbc.update("INSERT INTO live_forecast_issue(run_id,trial_id,captured_at,eligible,reason) VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",
                run,trial,ts(now),eligible,reason)>0;
    }
    void prices(String run,List<ForecastTypes.Point> ai,List<ForecastTypes.Point> b3) {
        for(int i=0;i<24;i++) jdbc.update("INSERT INTO live_forecast_price(run_id,horizon,target_period,ai,b3,threshold) VALUES(?,?,?,?,?,?)",
                run,i+1,ts(ai.get(i).targetPeriod()),ai.get(i).predictedUsep(),b3.get(i).predictedUsep(),ai.get(i).spikeThreshold());
    }
    void opportunity(String trial,String run,long home,long appliance,Instant issued,Instant usual,Instant ai,Instant b3,
                     BigDecimal kw,int minutes,BigDecimal weight,String bucket,JsonNode prediction) {
        jdbc.update("""
            INSERT INTO live_savings_opportunity(trial_id,run_id,household_id,appliance_id,issued_at,usual_start,ai_start,b3_start,
                power_kw,run_minutes,weight,bucket,prediction) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb) ON CONFLICT DO NOTHING
            """,trial,run,home,appliance,ts(issued),ts(usual),ts(ai),ts(b3),kw,minutes,weight,bucket,prediction.toString());
    }
    List<JsonNode> pendingOpportunities(String trial,Instant now) {
        return rows("""
            SELECT to_jsonb(o)::text FROM live_savings_opportunity o WHERE trial_id=? AND outcome IS NULL
            AND GREATEST(usual_start,ai_start,b3_start)+run_minutes*interval '1 minute'<=? ORDER BY issued_at LIMIT 2000
            """,trial,ts(now));
    }
    List<JsonNode> pendingPrices(String trial,Instant now) {
        return rows("""
            SELECT to_jsonb(p)::text FROM live_forecast_price p JOIN live_forecast_issue i ON i.run_id=p.run_id
            WHERE i.trial_id=? AND p.actual IS NULL AND p.target_period+interval '30 minutes'<=? ORDER BY target_period LIMIT 5000
            """,trial,ts(now));
    }
    void priceTruth(JsonNode row,MarketRevision truth,Instant now) {
        jdbc.update("UPDATE live_forecast_price SET actual=?,truth_revision=?,evaluated_at=? WHERE run_id=? AND horizon=? AND actual IS NULL",
                truth.usep(),truth.id(),ts(now),row.path("run_id").asText(),row.path("horizon").asInt());
    }
    void outcome(long id,JsonNode value,Instant now) {
        jdbc.update("UPDATE live_savings_opportunity SET outcome=?::jsonb,evaluated_at=? WHERE id=? AND outcome IS NULL",value.toString(),ts(now),id);
    }
    List<SavingsErrorRange.Sample> calibration(String trial,String method,String bucket,Instant asOf) {
        return jdbc.query("""
            SELECT (o.issued_at AT TIME ZONE 'Asia/Singapore')::date AS day,
                   (o.outcome->?->>'errorPerKwh')::numeric AS error
            FROM live_savings_opportunity o WHERE o.trial_id=? AND o.bucket=? AND o.evaluated_at<?
              AND (o.issued_at AT TIME ZONE 'Asia/Singapore')::date < (?::timestamptz AT TIME ZONE 'Asia/Singapore')::date
              AND NOT EXISTS (SELECT 1 FROM live_savings_opportunity p WHERE p.trial_id=o.trial_id AND p.bucket=o.bucket
                AND (p.issued_at AT TIME ZONE 'Asia/Singapore')::date=(o.issued_at AT TIME ZONE 'Asia/Singapore')::date
                AND (p.evaluated_at IS NULL OR p.evaluated_at>=?))
            """,(rs,n)->new SavingsErrorRange.Sample(rs.getDate("day").toLocalDate(),rs.getBigDecimal("error")),
            method,trial,bucket,ts(asOf),ts(asOf),ts(asOf));
    }
    List<JsonNode> opportunities(String trial) { return rows("SELECT to_jsonb(o)::text FROM live_savings_opportunity o WHERE trial_id=? ORDER BY issued_at,id",trial); }
    List<JsonNode> scoredPrices(String trial) { return rows("SELECT to_jsonb(p)::text FROM live_forecast_price p JOIN live_forecast_issue i ON i.run_id=p.run_id WHERE i.trial_id=? AND p.actual IS NOT NULL",trial); }
    Map<String,Object> availability(String trial) {
        return jdbc.queryForMap("""
            SELECT count(*) AS captured_runs,count(*) FILTER (WHERE eligible) AS ai_eligible_runs,
              count(*) FILTER (WHERE reason='AI_UNAVAILABLE_BASELINE_FALLBACK') AS baseline_fallback_runs,
              CASE WHEN count(*)=0 THEN NULL ELSE
                round((count(*) FILTER (WHERE eligible))::numeric/count(*),6) END AS ai_eligible_fraction,
              CASE WHEN count(*)=0 THEN NULL ELSE
                round((count(*) FILTER (WHERE reason='AI_UNAVAILABLE_BASELINE_FALLBACK'))::numeric/count(*),6) END AS baseline_fallback_fraction
            FROM live_forecast_issue WHERE trial_id=?
            """,trial);
    }
    List<Map<String,Object>> exclusions(String trial) { return jdbc.queryForList("SELECT COALESCE(reason,'ELIGIBLE') AS reason,count(*) AS count FROM live_forecast_issue WHERE trial_id=? GROUP BY reason",trial); }
    void proposal(long rec,long home,PriceForecast forecast,String method,JsonNode value) {
        jdbc.update("""
            INSERT INTO recommendation_savings_evidence(recommendation_id,household_id,forecast_run_id,model_version,method,proposal)
            VALUES(?,?,?,?,?,?::jsonb) ON CONFLICT DO NOTHING
            """,rec,home,forecast.runId(),forecast.modelVersion(),method,value.toString());
    }
    JsonNode evidence(long rec) { return rows("SELECT to_jsonb(e)::text FROM recommendation_savings_evidence e WHERE recommendation_id=?",rec).stream().findFirst().orElse(null); }
    void acceptance(long rec,JsonNode value) { jdbc.update("UPDATE recommendation_savings_evidence SET acceptance=?::jsonb WHERE recommendation_id=? AND acceptance IS NULL",value.toString(),rec); }
    List<JsonNode> acceptedEvidence(Instant now) {
        return rows("""
            SELECT to_jsonb(e)::text FROM recommendation_savings_evidence e JOIN recommendation r ON r.id=e.recommendation_id
            WHERE r.status='ACCEPTED' AND e.acceptance IS NOT NULL AND r.accepted_start<=?::timestamptz+interval '60 minutes'
              AND r.accepted_start+COALESCE(r.run_minutes,0)*interval '1 minute'>? ORDER BY e.recommendation_id
            FOR UPDATE OF e
            """,ts(now),ts(now));
    }
    void checked(long rec,JsonNode value) { jdbc.update("UPDATE recommendation_savings_evidence SET latest_check=?::jsonb WHERE recommendation_id=?",value.toString(),rec); }
    void alert(long rec,long home,Instant now,String direction,String text,BigDecimal saving,BigDecimal lower,BigDecimal upper) {
        jdbc.update("""
            INSERT INTO savings_change_alert(recommendation_id,household_id,created_at,updated_at,direction,message,saving_sgd,lower_sgd,upper_sgd)
            VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(recommendation_id,direction) DO UPDATE SET
              updated_at=EXCLUDED.updated_at,message=EXCLUDED.message,saving_sgd=EXCLUDED.saving_sgd,
              acknowledged_at=CASE WHEN savings_change_alert.resolved_at IS NOT NULL THEN NULL ELSE savings_change_alert.acknowledged_at END,
              resolved_at=NULL
            WHERE savings_change_alert.acknowledged_at IS NULL OR savings_change_alert.resolved_at IS NOT NULL
            """,rec,home,ts(now),ts(now),direction,text,saving,lower,upper);
    }
    void resolveAlerts(long rec,String exceptDirection,Instant now) {
        jdbc.update("UPDATE savings_change_alert SET resolved_at=? WHERE recommendation_id=? AND resolved_at IS NULL AND (?::text IS NULL OR direction<>?)",ts(now),rec,exceptDirection,exceptDirection);
    }
    List<JsonNode> alerts(long home,Instant now) { return rows("""
        SELECT to_jsonb(a)::text FROM savings_change_alert a
        JOIN recommendation r ON r.id=a.recommendation_id
        JOIN recommendation_savings_evidence e ON e.recommendation_id=r.id
        WHERE a.household_id=? AND a.acknowledged_at IS NULL AND a.resolved_at IS NULL
          AND r.status='ACCEPTED' AND r.accepted_start+COALESCE(r.run_minutes,0)*interval '1 minute'>?
          AND e.latest_check->>'status'='OUTSIDE_PAST_ERROR_RANGE'
          AND (e.latest_check->>'checkedAt')::timestamptz>=?::timestamptz-interval '2 minutes'
        ORDER BY a.updated_at DESC LIMIT 50
        """,home,ts(now),ts(now)); }
    boolean acknowledge(long home,long alert,Instant now) { return jdbc.update("UPDATE savings_change_alert SET acknowledged_at=COALESCE(acknowledged_at,?) WHERE household_id=? AND id=?",ts(now),home,alert)>0; }
    static Instant time(JsonNode row,String key) { return OffsetDateTime.parse(row.path(key).asText()).toInstant(); }
}

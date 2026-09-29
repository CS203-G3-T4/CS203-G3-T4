package sg.edu.smu.cs203.forecast;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static sg.edu.smu.cs203.forecast.ForecastTypes.*;

@Repository
public class ForecastRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public ForecastRepository(JdbcTemplate jdbc,ObjectMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }

    public static class ModelManifestConflict extends IllegalStateException {
        ModelManifestConflict(String version) { super("Model version changed manifest: "+version); }
    }

    @Transactional
    public boolean save(Run run, String snapshot, Map<String,List<Point>> candidates, JsonNode model) {
        // Check even on a duplicate run, within the same transaction as its points.
        if (model!=null) {
            String version=model.path("version").asText();
            jdbc.update("""
                    INSERT INTO model_run(version,manifest,registered_at,usable_from,promotion_state)
                    VALUES (?,?::jsonb,?,?,'APPROVED') ON CONFLICT(version) DO NOTHING
                    """,version,model.toString(),ts(run.generatedAt()),ts(Instant.parse(model.path("usableFrom").asText())));
            String existing=jdbc.queryForObject("SELECT manifest::text FROM model_run WHERE version=?",String.class,version);
            if (!mapper.readTree(existing).equals(model)) throw new ModelManifestConflict(version);
        }
        int inserted=jdbc.update("""
                INSERT INTO forecast_run(id,as_of,generated_at,origin_slot,input_revision,input_snapshot,mode,model_type,
                    model_version,selected_model,fallback_reason,quality_flags,stale,status)
                VALUES (?,?,?,?,?,?::jsonb,?,?,?,?,?,?::jsonb,?,?) ON CONFLICT DO NOTHING
                """,run.id(),ts(run.asOf()),ts(run.generatedAt()),ts(BaselineForecastService.floor(run.asOf())),
                run.inputRevision(),snapshot,run.mode(),run.modelType(),run.modelVersion(),run.selectedModel(),
                run.fallbackReason(),mapper.writeValueAsString(run.qualityFlags()),run.stale(),run.status());
        if (inserted==0) return false;
        for (var entry : candidates.entrySet()) {
            if (entry.getValue().size()!=24) throw new IllegalArgumentException("Incomplete candidate");
            for (Point p : entry.getValue()) {
                if (!p.targetPeriod().equals(BaselineForecastService.targets(run.asOf()).get(p.horizon()-1)))
                    throw new IllegalArgumentException("Target does not match origin");
                jdbc.update("""
                        INSERT INTO forecast_point(run_id,model_identifier,horizon,target_period,predicted_usep,spike_threshold,spike_flag)
                        VALUES (?,?,?,?,?,?,?)
                        """,run.id(),entry.getKey(),p.horizon(),ts(p.targetPeriod()),p.predictedUsep(),p.spikeThreshold(),p.spikeFlag());
            }
        }
        return true;
    }
    public Optional<Run> latest(String mode,Instant asOf) {
        return jdbc.query("""
                SELECT * FROM forecast_run WHERE mode=? AND as_of<=? AND generated_at<=?
                ORDER BY as_of DESC,generated_at DESC,sequence DESC LIMIT 1
                """,(rs,n) -> new Run(rs.getString("id"),rs.getTimestamp("as_of").toInstant(),
                rs.getTimestamp("generated_at").toInstant(),rs.getString("input_revision"),rs.getString("mode"),
                rs.getString("model_type"),rs.getString("model_version"),rs.getString("selected_model"),rs.getString("fallback_reason"),
                Arrays.asList(mapper.readValue(rs.getString("quality_flags"),String[].class)),rs.getBoolean("stale"),
                rs.getString("status"),List.of()),mode,ts(asOf),ts(asOf)).stream().findFirst().map(run -> new Run(
                run.id(),run.asOf(),run.generatedAt(),run.inputRevision(),run.mode(),run.modelType(),run.modelVersion(),
                run.selectedModel(),run.fallbackReason(),run.qualityFlags(),run.stale(),run.status(),points(run.id(),run.selectedModel())));
    }
    public List<Point> points(String runId,String model) {
        return jdbc.query("SELECT * FROM forecast_point WHERE run_id=? AND model_identifier=? ORDER BY horizon",
                (rs,n) -> new Point(rs.getInt("horizon"),rs.getTimestamp("target_period").toInstant(),
                rs.getBigDecimal("predicted_usep"),rs.getBigDecimal("spike_threshold"),rs.getObject("spike_flag",Boolean.class)),runId,model);
    }
    public List<String> baselineRanking(Instant asOf,String mode) {
        return jdbc.query("""
                SELECT m.manifest FROM model_run m JOIN forecast_run f ON f.model_version=m.version
                WHERE m.usable_from<=? AND f.as_of<=? AND f.generated_at<=? AND f.mode=? AND f.model_type='AI'
                ORDER BY f.as_of DESC,f.generated_at DESC,f.sequence DESC LIMIT 1
                """,(rs,n) -> {
                    JsonNode ranking=mapper.readTree(rs.getString(1)).path("baselineRanking");
                    return mapper.convertValue(ranking,String[].class);
                },ts(asOf),ts(asOf),ts(asOf),mode).stream().findFirst().map(Arrays::asList).orElse(List.of());
    }
    @Transactional
    public void evaluateAvailable(Instant asOf,String mode) {
        // Freeze first observed truth for this policy; FINAL is a distinct policy, never a rewritten prediction.
        jdbc.update("""
                INSERT INTO forecast_evaluation(run_id,model_identifier,horizon,truth_policy,truth_revision,actual_usep,evaluated_at)
                SELECT p.run_id,p.model_identifier,p.horizon,'PROVISIONAL_FIRST_EVALUATION',a.id,a.usep,?
                FROM forecast_point p JOIN forecast_run f ON f.id=p.run_id
                JOIN LATERAL (SELECT id,usep FROM market_price_revision
                    WHERE period_start=p.target_period AND price_status='PROVISIONAL' AND available_at<=? AND published_at<=?
                    ORDER BY published_at DESC,available_at DESC,id DESC LIMIT 1) a ON true
                WHERE f.mode=? AND f.as_of<=? AND f.generated_at<=? AND p.target_period+interval '30 minutes'<=?
                ON CONFLICT DO NOTHING
                """,ts(asOf),ts(asOf),ts(asOf),mode,ts(asOf),ts(asOf),ts(asOf));
        jdbc.update("""
                INSERT INTO forecast_evaluation(run_id,model_identifier,horizon,truth_policy,truth_revision,actual_usep,evaluated_at)
                SELECT p.run_id,p.model_identifier,p.horizon,'FINAL',min(a.id),min(a.usep),?
                FROM forecast_point p JOIN forecast_run f ON f.id=p.run_id
                JOIN market_price_revision a ON a.period_start=p.target_period AND a.price_status='FINAL'
                WHERE f.mode=? AND f.as_of<=? AND f.generated_at<=? AND p.target_period+interval '30 minutes'<=?
                GROUP BY p.run_id,p.model_identifier,p.horizon HAVING count(DISTINCT a.usep)=1
                ON CONFLICT DO NOTHING
                """,ts(asOf),mode,ts(asOf),ts(asOf),ts(asOf));
    }
    public Map<String,Object> accuracy(int days,Instant asOf,String mode) {
        List<Map<String,Object>> rows=jdbc.queryForList("""
                SELECT p.model_identifier AS model,
                    CASE WHEN p.model_identifier='AI' THEN f.model_version ELSE p.model_identifier||'-v1' END AS "modelVersion",
                    p.horizon,e.truth_policy AS "truthPolicy",count(*) AS pairs,
                    avg(abs(p.predicted_usep-e.actual_usep)) AS mae,
                    count(*) FILTER(WHERE p.spike_flag AND e.actual_usep>p.spike_threshold) AS tp,
                    count(*) FILTER(WHERE p.spike_flag AND e.actual_usep<=p.spike_threshold) AS fp,
                    count(*) FILTER(WHERE NOT p.spike_flag AND e.actual_usep>p.spike_threshold) AS fn,
                    count(p.spike_threshold) AS "assessedPairs"
                FROM forecast_evaluation e JOIN forecast_point p USING(run_id,model_identifier,horizon)
                JOIN forecast_run f ON f.id=p.run_id
                WHERE f.mode=? AND f.as_of>=? AND f.as_of<=? AND f.generated_at<=? AND e.evaluated_at<=?
                GROUP BY p.model_identifier,"modelVersion",p.horizon,e.truth_policy
                ORDER BY p.model_identifier,"modelVersion",p.horizon,e.truth_policy
                """,mode,ts(asOf.minus(Duration.ofDays(days))),ts(asOf),ts(asOf),ts(asOf));
        return Map.of("mode",mode,"days",days,"asOf",asOf,"units","SGD_PER_MWH","scores",rows,
                "comparison","Descriptive per-method coverage; offline reports use common pairs",
                "available",!rows.isEmpty());
    }
    private static Timestamp ts(Instant value) { return Timestamp.from(value); }
}

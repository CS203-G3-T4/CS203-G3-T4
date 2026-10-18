package sg.edu.smu.cs203.forecast;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class ForecastOperationsRepository {
    public record Demand(Instant targetPeriod,Instant publishedAt,Instant availableAt,BigDecimal demandMw,String source) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public ForecastOperationsRepository(JdbcTemplate jdbc,ObjectMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }
    public List<Demand> demand(Instant origin) {
        return jdbc.query("""
                SELECT DISTINCT ON(target_period) * FROM expected_demand_revision
                WHERE published_at<=? AND available_at<=? AND target_period>? AND target_period<=?
                ORDER BY target_period,published_at DESC,available_at DESC,source
                """,(r,n)->new Demand(r.getTimestamp("target_period").toInstant(),r.getTimestamp("published_at").toInstant(),
                r.getTimestamp("available_at").toInstant(),r.getBigDecimal("demand_mw"),r.getString("source")),
                ts(origin),ts(origin),ts(BaselineForecastService.floor(origin)),ts(BaselineForecastService.floor(origin).plusSeconds(43200)));
    }
    @Transactional public void saveDemand(List<Demand> rows,Instant now) {
        if (rows.size()>1440) throw new IllegalArgumentException("Too many demand vintages");
        for (Demand row:rows) {
            if (row.targetPeriod()==null || !BaselineForecastService.floor(row.targetPeriod()).equals(row.targetPeriod())
                    || row.publishedAt()==null || row.availableAt()==null || row.publishedAt().isAfter(row.availableAt())
                    || row.availableAt().isAfter(now) || row.demandMw()==null || row.demandMw().signum()<0
                    || row.source()==null || row.source().isBlank()) throw new IllegalArgumentException("Invalid demand provenance");
            jdbc.update("""
                INSERT INTO expected_demand_revision (target_period,published_at,available_at,demand_mw,source) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING
                """,ts(row.targetPeriod()),ts(row.publishedAt()),ts(row.availableAt()),row.demandMw(),row.source());
            BigDecimal saved=jdbc.queryForObject("SELECT demand_mw FROM expected_demand_revision WHERE target_period=? AND published_at=? AND available_at=? AND source=?",
                    BigDecimal.class,ts(row.targetPeriod()),ts(row.publishedAt()),ts(row.availableAt()),row.source());
            if (saved.compareTo(row.demandMw())!=0) throw new IllegalArgumentException("Demand vintage changed value");
        }
    }
    public List<String> ranking(Instant origin,String mode) {
        return jdbc.query("SELECT evidence FROM baseline_validation WHERE mode=? AND usable_from<=? ORDER BY usable_from DESC,id DESC LIMIT 1",
                (r,n)->List.of(mapper.convertValue(mapper.readTree(r.getString(1)).path("ranking"),String[].class)),mode,ts(origin))
                .stream().findFirst().orElse(List.of());
    }
    @Transactional public void saveRanking(JsonNode evidence,Instant now) {
        String id=evidence.path("id").asText(), mode=evidence.path("mode").asText();
        Instant start=Instant.parse(evidence.path("start").asText()),end=Instant.parse(evidence.path("end").asText());
        List<String> ranking=List.of(mapper.convertValue(evidence.path("ranking"),String[].class));
        if (id.isBlank() || !List.of("LIVE","REPLAY").contains(mode) || !start.isBefore(end) || end.isAfter(now)
                || !new java.util.HashSet<>(ranking).equals(java.util.Set.of("B1","B2","B3")) || ranking.size()!=3
                || evidence.path("commonOriginDays").asInt()<7 || evidence.path("commonPairs").asInt()<168
                || !evidence.path("datasetDigest").asText().matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Require a prior, common-pair B1-B3 validation with at least seven days");
        for (String name:ranking) {
            JsonNode score=evidence.path("table").path(name);
            if (!score.path("mae").isNumber() || score.path("mae").asDouble()<0 || !Double.isFinite(score.path("mae").asDouble())
                    || score.path("commonPairs").asInt()!=evidence.path("commonPairs").asInt())
                throw new IllegalArgumentException("Baseline scores must use identical pairs");
        }
        for (int i=1;i<ranking.size();i++) if (evidence.path("table").path(ranking.get(i-1)).path("mae").asDouble()
                >evidence.path("table").path(ranking.get(i)).path("mae").asDouble()) throw new IllegalArgumentException("Invalid ranking order");
        jdbc.update("INSERT INTO baseline_validation (id,mode,usable_from,validation_start,validation_end,dataset_digest,evidence) VALUES (?,?,?,?,?,?,?::jsonb) ON CONFLICT DO NOTHING",id,mode,ts(now),ts(start),ts(end),
                evidence.path("datasetDigest").asText(),evidence.toString());
        String saved=jdbc.queryForObject("SELECT evidence::text FROM baseline_validation WHERE id=?",String.class,id);
        if (!mapper.readTree(saved).equals(evidence)) throw new IllegalArgumentException("Validation evidence is immutable");
    }
    @Transactional public void recordAttempt(JsonNode value,Instant now) {
        String id=value.path("attemptId").asText(), state=value.path("state").asText();
        if (!id.matches("[A-Za-z0-9_.-]{1,128}") || !List.of("RUNNING","SUCCESS","REJECTED","FAILED").contains(state)
                || value.path("modelVersion").asText().isBlank() || value.toString().length()>512000)
            throw new IllegalArgumentException("Invalid training attempt");
        Instant training=Instant.parse(value.path("trainingDate").asText());
        String version="attempt:"+id;
        jdbc.update("""
            INSERT INTO model_run(version,manifest,registered_at,usable_from,promotion_state,attempt_id,model_version,training_date,
                dataset_digest,scores,outcome,reason,promotion_decision,training_start,training_end)
            VALUES (?,?::jsonb,?,?,'TRAINING_ATTEMPT',?,?,?,?,?::jsonb,?,?,?,?,?)
            ON CONFLICT(version) DO UPDATE SET manifest=EXCLUDED.manifest,dataset_digest=EXCLUDED.dataset_digest,
                scores=EXCLUDED.scores,outcome=EXCLUDED.outcome,reason=EXCLUDED.reason,promotion_decision=EXCLUDED.promotion_decision,
                training_start=EXCLUDED.training_start,training_end=EXCLUDED.training_end
            WHERE model_run.outcome='RUNNING' AND model_run.attempt_id=EXCLUDED.attempt_id AND model_run.model_version=EXCLUDED.model_version
            """,version,value.toString(),ts(now),ts(training),id,value.path("modelVersion").asText(),ts(training),
                value.path("datasetDigest").asText(),value.path("scores").isMissingNode()?"{}":value.path("scores").toString(),state,
                value.path("reason").asText(),value.path("promotionDecision").asText(),optionalTime(value,"trainingStart"),optionalTime(value,"trainingEnd"));
        if (!mapper.readTree(jdbc.queryForObject("SELECT manifest::text FROM model_run WHERE version=?",String.class,version)).equals(value))
            throw new IllegalArgumentException("Completed attempts are immutable");
    }
    public List<JsonNode> attempts(int limit) {
        if (limit<1 || limit>100) throw new IllegalArgumentException("limit must be1..100");
        return jdbc.query("SELECT manifest FROM model_run WHERE attempt_id IS NOT NULL ORDER BY training_date DESC,version DESC LIMIT ?",
                (row,n)->mapper.readTree(row.getString(1)),limit);
    }
    private static Timestamp optionalTime(JsonNode node,String key) { return node.path(key).asText().isBlank()?null:ts(Instant.parse(node.path(key).asText())); }
    private static Timestamp ts(Instant value) { return Timestamp.from(value); }
}

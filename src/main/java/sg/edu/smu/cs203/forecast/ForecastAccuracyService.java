package sg.edu.smu.cs203.forecast;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ForecastAccuracyService {
    public record Sample(String runId,Instant asOf,Instant targetPeriod,String model,String modelVersion,int horizon,
                         BigDecimal predictedUsep,BigDecimal actualUsep,String truthPolicy,BigDecimal threshold,Boolean spikeFlag) {}
    private final JdbcTemplate jdbc;
    private final ForecastRepository repository;
    public ForecastAccuracyService(JdbcTemplate jdbc,ForecastRepository repository) { this.jdbc=jdbc; this.repository=repository; }
    public Map<String,Object> read(int days,int horizon,String policy,Instant now,String mode) {
        if (horizon<1 || horizon>24 || !List.of("FINAL","PROVISIONAL_FIRST_EVALUATION").contains(policy))
            throw new IllegalArgumentException("horizon must be 1..24; choose FINAL or PROVISIONAL_FIRST_EVALUATION");
        List<Sample> series=jdbc.query("""
            SELECT f.id,f.as_of,p.target_period,p.model_identifier,
                CASE WHEN p.model_identifier='AI' THEN f.model_version ELSE p.model_identifier||'-v1' END AS version,
                p.horizon,p.predicted_usep,e.actual_usep,p.spike_threshold,p.spike_flag
            FROM forecast_point p JOIN forecast_run f ON f.id=p.run_id
            LEFT JOIN forecast_evaluation e ON e.run_id=p.run_id AND e.model_identifier=p.model_identifier
                AND e.horizon=p.horizon AND e.truth_policy=? AND e.evaluated_at<=?
            WHERE f.mode=? AND f.as_of>=? AND f.as_of<=? AND f.generated_at<=? AND p.horizon=?
                AND p.target_period+interval '30 minutes'<=?
            ORDER BY f.as_of,f.id,p.model_identifier
            """,(r,n)->new Sample(r.getString("id"),r.getTimestamp("as_of").toInstant(),r.getTimestamp("target_period").toInstant(),
                r.getString("model_identifier"),r.getString("version"),r.getInt("horizon"),r.getBigDecimal("predicted_usep"),
                r.getBigDecimal("actual_usep"),policy,r.getBigDecimal("spike_threshold"),r.getObject("spike_flag",Boolean.class)),
                policy,Timestamp.from(now),mode,Timestamp.from(now.minus(Duration.ofDays(days))),Timestamp.from(now),Timestamp.from(now),horizon,Timestamp.from(now));
        List<String> methods=new ArrayList<>(BaselineForecastService.DEFAULT_ORDER);
        if (series.stream().anyMatch(p->p.model().equals("AI"))) methods.add("AI");
        Map<String,List<Sample>> byRun=new LinkedHashMap<>();
        for (Sample row:series) byRun.computeIfAbsent(row.runId(),k->new ArrayList<>()).add(row);
        Set<String> common=new HashSet<>();
        for (var entry:byRun.entrySet()) if (entry.getValue().stream().filter(p->p.actualUsep()!=null).map(Sample::model).distinct().toList().containsAll(methods)
                && entry.getValue().stream().filter(p->p.actualUsep()!=null).map(Sample::actualUsep).map(BigDecimal::stripTrailingZeros).distinct().count()==1) common.add(entry.getKey());
        List<Map<String,Object>> scores=new ArrayList<>();
        for (String method:methods) {
            var own=series.stream().filter(p->p.model().equals(method)).toList();
            var eligible=own.stream().filter(p->p.actualUsep()!=null).toList();
            var paired=eligible.stream().filter(p->common.contains(p.runId())).toList();
            Map<String,Object> score=new LinkedHashMap<>();
            score.put("model",method); score.put("modelVersions",own.stream().map(Sample::modelVersion).distinct().toList());
            score.put("savedPairs",own.size()); score.put("eligiblePairs",eligible.size()); score.put("missingActualPairs",own.size()-eligible.size());
            score.put("commonPairs",paired.size()); score.put("mae",mae(paired)); score.put("ownCoverageMae",mae(eligible));
            scores.add(score);
        }
        Map<String,Object> result=new LinkedHashMap<>(repository.accuracy(days,now,mode));
        result.put("series",series); result.put("commonScores",scores); result.put("comparisonMethods",methods);
        result.put("comparison","MAE on identical saved run/horizon/actual pairs across comparisonMethods; ownCoverageMae is descriptive only");
        result.put("horizon",horizon); result.put("truthPolicy",policy); result.put("commonPairs",common.size());
        result.put("available",!series.isEmpty()); return result;
    }
    private static BigDecimal mae(List<Sample> rows) {
        return rows.isEmpty()?null:rows.stream().map(p->p.predictedUsep().subtract(p.actualUsep()).abs()).reduce(BigDecimal.ZERO,BigDecimal::add)
                .divide(BigDecimal.valueOf(rows.size()),10,RoundingMode.HALF_UP);
    }
}

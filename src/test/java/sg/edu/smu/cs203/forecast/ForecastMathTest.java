package sg.edu.smu.cs203.forecast;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import sg.edu.smu.cs203.market.price.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ForecastMathTest {
    static final Instant NOW=Instant.parse("2026-01-08T16:03:00Z");
    static final Clock CLOCK=Clock.fixed(NOW,ZoneId.of("Asia/Singapore"));
    static MarketRevision row(Instant period,BigDecimal value) {
        return new MarketRevision(1,"SYNTHETIC",period,period.plusSeconds(60),period.plusSeconds(60),value,
                BigDecimal.valueOf(6000),null,"PROVISIONAL","EMC_PERIOD");
    }
    static List<MarketRevision> rows() {
        List<MarketRevision> result=new ArrayList<>();
        Instant start=Instant.parse("2025-12-31T16:00:00Z");
        for (int i=0;i<384;i++) result.add(row(start.plusSeconds(i*1800L),BigDecimal.valueOf(-10+i)));
        return result;
    }
    @Test
    void matchesLanguageNeutralGoldenFixtureIncludingSingaporeMidnight() {
        JsonNode gold=new ObjectMapper().readTree(Path.of("ml/fixtures/baseline-golden.json").toFile());
        var predictions=new BaselineForecastService().calculate(rows(),NOW);
        for (String method:BaselineForecastService.DEFAULT_ORDER) {
            for (int i=0;i<24;i++) assertThat(predictions.get(method).get(i)).isEqualByComparingTo(gold.path(method).get(i).decimalValue());
        }
        var targets=BaselineForecastService.targets(NOW);
        assertThat(targets).hasSize(24).isSorted();
        assertThat(targets.getFirst()).isEqualTo(Instant.parse("2026-01-08T16:30:00Z"));
        assertThat(targets.getLast()).isEqualTo(Instant.parse("2026-01-09T04:00:00Z"));
        assertThat(BaselineForecastService.targets(BaselineForecastService.floor(NOW))).isEqualTo(targets);
        var current=new ArrayList<>(rows());
        current.add(row(BaselineForecastService.floor(NOW),BigDecimal.valueOf(1000)));
        assertThat(new BaselineForecastService().calculate(current,NOW).get("B1").getFirst())
                .isEqualByComparingTo("475.8333333333");
    }
    @Test
    void missingIntervalsAndDelayedInputsNeverBecomeShiftedLags() {
        List<MarketRevision> rows=new ArrayList<>(rows()); rows.removeLast();
        assertThat(new BaselineForecastService().calculate(rows,NOW)).doesNotContainKey("B1");
        rows.add(new MarketRevision(2,"SYNTHETIC",BaselineForecastService.floor(NOW).minusSeconds(1800),
                NOW.minusSeconds(60),NOW.plusSeconds(60),BigDecimal.ZERO,null,null,"PROVISIONAL","EMC_PERIOD"));
        assertThat(new BaselineForecastService().calculate(rows,NOW)).doesNotContainKey("B1");
        assertThat(new BaselineForecastService().calculate(List.of(),NOW)).isEmpty();
    }
    @Test
    void zeroMadNegativeReferenceAndInsufficientSamplesAreExplicit() {
        var repo=mock(MarketHistoryRepository.class); var service=mock(ForecastService.class);
        var assessment=new CurrentAssessmentService(repo,CLOCK,service,14,BigDecimal.valueOf(3),BigDecimal.ONE,BigDecimal.ONE);
        var rows=new ArrayList<MarketRevision>(); Instant target=BaselineForecastService.targets(NOW).getFirst();
        for (int d=1;d<=28;d++) rows.add(row(target.minus(Duration.ofDays(d)),BigDecimal.valueOf(-5)));
        var ref=assessment.reference(rows,target,NOW);
        assertThat(ref.threshold()).isEqualByComparingTo("-2");
        assertThat(assessment.reference(rows.subList(0,13),target,NOW).available()).isFalse();
        Instant current=BaselineForecastService.floor(NOW);
        rows.clear();
        for (int d=28;d>=0;d--) rows.add(row(current.minus(Duration.ofDays(d)),BigDecimal.ZERO));
        when(repo.asOf(NOW)).thenReturn(rows);
        assertThat(assessment.current().deviationPercent()).isNull();
        assertThat(assessment.current().classification()).isEqualTo("NORMAL");
    }
}

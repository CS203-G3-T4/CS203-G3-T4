package sg.edu.smu.cs203.forecast;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import sg.edu.smu.cs203.market.price.MarketHistoryRepository;
import sg.edu.smu.cs203.market.price.MarketRevision;
import tools.jackson.databind.JsonNode;
import static sg.edu.smu.cs203.forecast.ForecastTypes.*;

@Service
public class CurrentAssessmentService {
    private final MarketHistoryRepository history;
    private final Clock clock;
    private final int minimum;
    private final BigDecimal k, spreadFloor, band;
    private final ForecastService forecasts;

    public CurrentAssessmentService(MarketHistoryRepository history, Clock clock, ForecastService forecasts,
            @Value("${forecast.spike.minimum-samples:14}") int minimum,
            @Value("${forecast.spike.k:3}") BigDecimal k,
            @Value("${forecast.spike.spread-floor:1}") BigDecimal spreadFloor,
            @Value("${forecast.spike.classification-band:1}") BigDecimal band) {
        if (minimum<1 || minimum>28 || k.signum()<=0 || spreadFloor.signum()<=0 || band.signum()<=0)
            throw new IllegalArgumentException("Invalid spike configuration");
        this.history=history; this.clock=clock; this.forecasts=forecasts;
        this.minimum=minimum; this.k=k; this.spreadFloor=spreadFloor; this.band=band;
    }
    /** An AI run must use the same spike definition as its evaluated artifact. */
    public void requireModelConfig(JsonNode config) {
        if (!config.path("minimumSamples").isIntegralNumber() || config.path("minimumSamples").asInt()!=minimum
                || !config.path("k").isNumber() || config.path("k").decimalValue().compareTo(k)!=0
                || !config.path("spreadFloor").isNumber() || config.path("spreadFloor").decimalValue().compareTo(spreadFloor)!=0)
            throw new IllegalArgumentException("Model spike configuration differs from Spring configuration");
    }
    public Reference reference(List<MarketRevision> rows, Instant target, Instant asOf) {
        var values=BaselineForecastService.values(rows,asOf);
        List<BigDecimal> prices=new ArrayList<>();
        for (int d=1;d<=28;d++) {
            Instant past=target.minus(Duration.ofDays(d));
            if (past.isBefore(asOf) && values.containsKey(past)) prices.add(values.get(past));
        }
        if (prices.size()<minimum) return new Reference(false,null,null,null,prices.size(),28);
        BigDecimal typical=median(prices);
        BigDecimal mad=median(prices.stream().map(p -> p.subtract(typical).abs()).toList());
        BigDecimal spread=mad.multiply(new BigDecimal("1.4826")).max(spreadFloor);
        return new Reference(true,typical,spread,typical.add(k.multiply(spread)),prices.size(),28);
    }
    public Assessment current() {
        Instant asOf=clock.instant();
        List<MarketRevision> rows=history.asOf(asOf);
        if (rows.isEmpty()) return new Assessment(false,true,null,null,null,null,null,"UNAVAILABLE",0,28,"NO_PRICE");
        MarketRevision current=rows.getLast();
        boolean stale=forecasts.stale(rows,asOf);
        Reference ref=reference(rows,current.periodStart(),asOf);
        if (!ref.available()) return new Assessment(false,stale,current.usep(),null,null,null,null,
                "UNAVAILABLE",ref.sampleCount(),28,"INSUFFICIENT_REFERENCE_HISTORY");
        BigDecimal delta=current.usep().subtract(ref.typical());
        BigDecimal pct=ref.typical().signum()>0 ? delta.multiply(BigDecimal.valueOf(100))
                .divide(ref.typical(),4,RoundingMode.HALF_UP) : null;
        String classification=current.usep().compareTo(ref.threshold())>0 ? "SPIKE"
                : delta.compareTo(ref.spread().multiply(band))>0 ? "EXPENSIVE"
                : delta.compareTo(ref.spread().multiply(band).negate())<0 ? "CHEAP" : "NORMAL";
        return new Assessment(true,stale,current.usep(),ref.typical(),delta,pct,ref.threshold(),classification,
                ref.sampleCount(),28,forecasts.mappingVerified(rows) ? null : "PERIOD_MAPPING_UNVERIFIED");
    }
    private static BigDecimal median(List<BigDecimal> values) {
        List<BigDecimal> sorted=values.stream().sorted().toList();
        int n=sorted.size();
        return n%2==1 ? sorted.get(n/2) : sorted.get(n/2-1).add(sorted.get(n/2)).divide(BigDecimal.TWO);
    }
}

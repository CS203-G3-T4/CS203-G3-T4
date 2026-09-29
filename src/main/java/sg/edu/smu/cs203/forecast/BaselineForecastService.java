package sg.edu.smu.cs203.forecast;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import sg.edu.smu.cs203.market.price.MarketRevision;

@Service
public class BaselineForecastService {
    public static final List<String> DEFAULT_ORDER = List.of("B1", "B2", "B3");
    public static Instant floor(Instant value) {
        return Instant.ofEpochSecond(Math.floorDiv(value.getEpochSecond(),1800)*1800);
    }
    public static List<Instant> targets(Instant asOf) {
        return java.util.stream.IntStream.rangeClosed(1,24).mapToObj(h -> floor(asOf).plusSeconds(h*1800L)).toList();
    }
    public static Map<Instant,BigDecimal> values(List<MarketRevision> history, Instant asOf) {
        Map<Instant,BigDecimal> values = new LinkedHashMap<>();
        for (MarketRevision r : history) {
            if (!r.periodStart().isAfter(asOf) && r.availableAt()!=null && !r.availableAt().isAfter(asOf)
                    && r.sourceUpdatedAt()!=null && !r.sourceUpdatedAt().isAfter(asOf)) values.put(r.periodStart(),r.usep());
        }
        return values;
    }
    public Map<String,List<BigDecimal>> calculate(List<MarketRevision> history, Instant asOf) {
        Map<Instant,BigDecimal> prices = values(history,asOf);
        Map<String,List<BigDecimal>> result = new LinkedHashMap<>();
        List<BigDecimal> recent = new ArrayList<>();
        Instant latest=prices.containsKey(floor(asOf)) ? floor(asOf) : floor(asOf).minusSeconds(1800);
        for (int i=0;i<6;i++) recent.add(prices.get(latest.minusSeconds(i*1800L)));
        if (!recent.contains(null)) result.put("B1",java.util.Collections.nCopies(24,mean(recent)));
        for (int days : List.of(1,7)) {
            List<BigDecimal> predictions = new ArrayList<>();
            for (Instant target : targets(asOf)) {
                List<BigDecimal> previous = new ArrayList<>();
                for (int day=1;day<=days;day++) previous.add(prices.get(target.minus(Duration.ofDays(day))));
                if (previous.contains(null)) break;
                predictions.add(mean(previous));
            }
            if (predictions.size()==24) result.put(days==1 ? "B2" : "B3",List.copyOf(predictions));
        }
        return result;
    }
    private static BigDecimal mean(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO,BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()),10,RoundingMode.HALF_UP);
    }
}

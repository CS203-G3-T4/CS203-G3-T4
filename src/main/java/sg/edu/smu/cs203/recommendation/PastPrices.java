package sg.edu.smu.cs203.recommendation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** Actual half-hourly USEP (SGD/MWh) known by asOf, for the confidence check (CSDT4-39). Read-only. */
public interface PastPrices {

    /** Period start -> actual price, for roughly the 4 weeks before asOf. Never filled in or guessed. */
    Map<Instant, BigDecimal> actualPrices(Instant asOf);

    PastPrices NONE = asOf -> Map.of();
}

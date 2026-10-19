package sg.edu.smu.cs203.forecast;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** A predictor returns 24 ordered half-hours, or an empty list when inputs are insufficient. */
public interface ForecastPredictor {
    String identifier();
    List<BigDecimal> predict(Map<Instant,BigDecimal> knownPrices, Instant asOf);
}

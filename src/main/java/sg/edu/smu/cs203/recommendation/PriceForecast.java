package sg.edu.smu.cs203.recommendation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Forecast USEP for the coming half-hours, in the shape the recommendation engine needs.
 *
 * @param source DEMO_FORECAST (fixed replay-day curve) or F3_FORECAST (F3's saved forecast)
 * @param model  which model produced it, e.g. "B2" or "AI" for F3, or the replay day for the demo
 * @param points one price per half-hour, in time order, each starting on a :00 or :30 boundary
 */
public record PriceForecast(String source, String model, Instant asOf, List<HalfHourPrice> points) {

    public static final String DEMO = "DEMO_FORECAST";
    public static final String F3 = "F3_FORECAST";

    public PriceForecast {
        points = List.copyOf(points);
    }

    public boolean isDemo() {
        return DEMO.equals(source);
    }

    /** Forecast USEP for the half-hour that starts at periodStart. */
    public record HalfHourPrice(Instant periodStart, BigDecimal usepSgdPerMwh) {
    }
}

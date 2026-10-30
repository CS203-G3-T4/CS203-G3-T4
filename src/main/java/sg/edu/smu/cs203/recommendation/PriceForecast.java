package sg.edu.smu.cs203.recommendation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Forecast USEP for the coming half-hours, in the shape the recommendation engine needs.
 *
 * @param source DEMO_FORECAST (fixed replay-day curve) or F3_FORECAST (F3's saved forecast)
 * @param model  which model produced it, e.g. "B2" or "AI" for F3, or the replay day for the demo
 * @param points one price per half-hour, in time order, each starting on a :00 or :30 boundary
 * @param replayDay for the demo forecast, the past day it replays (Singapore date); null for F3.
 *                  Confidence (CSDT4-39) is then judged on the weeks before that day.
 */
public record PriceForecast(String source, String model, Instant asOf, List<HalfHourPrice> points,
                            LocalDate replayDay, String runId, String modelVersion) {

    public static final String DEMO = "DEMO_FORECAST";
    public static final String F3 = "F3_FORECAST";

    public PriceForecast {
        points = List.copyOf(points);
    }

    public PriceForecast(String source, String model, Instant asOf, List<HalfHourPrice> points) {
        this(source, model, asOf, points, null, null, null);
    }

    public PriceForecast(String source,String model,Instant asOf,List<HalfHourPrice> points,LocalDate replayDay) {
        this(source,model,asOf,points,replayDay,null,null);
    }

    public boolean isDemo() {
        return DEMO.equals(source);
    }

    /**
     * Forecast USEP for the half-hour that starts at periodStart. spike is true when the price is
     * far above what is normal for that time of day (F3's rule: above the 28-day typical price plus
     * k spreads; CSDT4-38).
     */
    public record HalfHourPrice(Instant periodStart, BigDecimal usepSgdPerMwh, boolean spike) {

        public HalfHourPrice(Instant periodStart, BigDecimal usepSgdPerMwh) {
            this(periodStart, usepSgdPerMwh, false);
        }
    }
}

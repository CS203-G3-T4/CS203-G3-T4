package sg.edu.smu.cs203.recommendation;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What the pages receive. Costs and saving are SGD for one run. demoForecast is true when the
 * suggestion came from the demo curve, so the page can label it rather than call it a prediction.
 */
public record RecommendationResponse(
        long id,
        long householdId,
        long applianceId,
        String applianceName,
        String kind,
        Instant suggestedStart,
        Instant usualStart,
        Instant acceptedStart,
        BigDecimal suggestedCostSgd,
        BigDecimal usualCostSgd,
        BigDecimal estSavingSgd,
        String reason,
        String forecastSource,
        String forecastModel,
        boolean demoForecast,
        RecommendationStatus status,
        Instant createdAt,
        Instant decidedAt) {

    public static RecommendationResponse from(Recommendation r) {
        return new RecommendationResponse(
                r.id(),
                r.householdId(),
                r.applianceId(),
                r.applianceName(),
                r.kind(),
                r.suggestedStart(),
                r.usualStart(),
                r.acceptedStart(),
                r.suggestedCost(),
                r.usualCost(),
                r.estSaving(),
                r.reason(),
                r.forecastSource(),
                r.forecastModel(),
                PriceForecast.DEMO.equals(r.forecastSource()),
                r.status(),
                r.createdAt(),
                r.decidedAt());
    }
}

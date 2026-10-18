package sg.edu.smu.cs203.recommendation;

import java.math.BigDecimal;
import java.time.Instant;

/** One row of the recommendation table (money in SGD per run). */
public record Recommendation(
        Long id,
        long householdId,
        long applianceId,
        String applianceName,
        String kind,
        Instant suggestedStart,
        Instant usualStart,
        BigDecimal suggestedCost,
        BigDecimal usualCost,
        BigDecimal estSaving,
        String reason,
        String forecastSource,
        String forecastModel,
        RecommendationStatus status,
        Instant acceptedStart,
        Instant createdAt,
        Instant decidedAt) {

    /** The only kind so far: move a flexible appliance's start time. */
    public static final String SHIFT_START = "SHIFT_START";
}

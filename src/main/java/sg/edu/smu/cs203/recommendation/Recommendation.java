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
        Instant decidedAt,
        Instant spikeStart,
        Instant snoozedUntil) {

    /** Move a flexible appliance's start time to a cheaper one (CSDT4-19). */
    public static final String SHIFT_START = "SHIFT_START";
    /**
     * Warn that a price spike is forecast during the usual run (CSDT4-38). suggestedStart is the
     * spike-free alternative and estSaving the cost avoided; spikeStart is set.
     */
    public static final String AVOID = "AVOID";

    /** A suggestion that is not a spike warning and not snoozed. */
    public Recommendation(Long id, long householdId, long applianceId, String applianceName, String kind,
                          Instant suggestedStart, Instant usualStart, BigDecimal suggestedCost,
                          BigDecimal usualCost, BigDecimal estSaving, String reason, String forecastSource,
                          String forecastModel, RecommendationStatus status, Instant acceptedStart,
                          Instant createdAt, Instant decidedAt) {
        this(id, householdId, applianceId, applianceName, kind, suggestedStart, usualStart, suggestedCost,
                usualCost, estSaving, reason, forecastSource, forecastModel, status, acceptedStart, createdAt,
                decidedAt, null, null);
    }

    public boolean isWarning() {
        return AVOID.equals(kind);
    }

    /** True while "Remind me later" hides it. */
    public boolean snoozedAt(Instant now) {
        return snoozedUntil != null && snoozedUntil.isAfter(now);
    }
}

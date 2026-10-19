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
        Instant snoozedUntil,
        Integer confidenceCheaperDays,
        Integer confidenceDaysChecked) {

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
                decidedAt, null, null, null, null);
    }

    /** As above, with the spike warning fields (CSDT4-38) but no confidence. */
    public Recommendation(Long id, long householdId, long applianceId, String applianceName, String kind,
                          Instant suggestedStart, Instant usualStart, BigDecimal suggestedCost,
                          BigDecimal usualCost, BigDecimal estSaving, String reason, String forecastSource,
                          String forecastModel, RecommendationStatus status, Instant acceptedStart,
                          Instant createdAt, Instant decidedAt, Instant spikeStart, Instant snoozedUntil) {
        this(id, householdId, applianceId, applianceName, kind, suggestedStart, usualStart, suggestedCost,
                usualCost, estSaving, reason, forecastSource, forecastModel, status, acceptedStart, createdAt,
                decidedAt, spikeStart, snoozedUntil, null, null);
    }

    public boolean isWarning() {
        return AVOID.equals(kind);
    }

    /**
     * When this suggestion stops being useful: its start (it then expires), or for a warning the
     * earlier of the alternative start and the usual start it warns about. "Remind me later" must
     * bring it back before this (CSDT4-40).
     */
    public Instant deadline() {
        return deadline(isWarning(), suggestedStart, usualStart);
    }

    static Instant deadline(boolean warning, Instant suggestedStart, Instant usualStart) {
        return warning && usualStart.isBefore(suggestedStart) ? usualStart : suggestedStart;
    }

    /** True while "Remind me later" hides it. */
    public boolean snoozedAt(Instant now) {
        return snoozedUntil != null && snoozedUntil.isAfter(now);
    }
}

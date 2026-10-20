package sg.edu.smu.cs203.recommendation;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What the pages receive. Costs and saving are SGD for one run. demoForecast is true when the
 * suggestion came from the demo curve, so the page can label it rather than call it a prediction.
 *
 * kind AVOID is a spike warning (CSDT4-38): usualStart is the time to avoid, spikeStart the first
 * spike half-hour, suggestedStart the spike-free alternative and estSavingSgd the cost avoided.
 * remindAt is set after "Remind me later".
 *
 * CSDT4-39: reason explains the suggestion with the forecast prices (¢/kWh), times and window
 * limit. confidence* say on how many of the last 28 days (with actual prices) the suggested time
 * was really cheaper; all null when there wasn't enough history. savingEstimated is true while the
 * saving comes from the forecast.
 *
 * CSDT4-53: realisedSavingSgd is what an accepted suggestion really saved at actual prices (cost
 * at the usual time minus cost at the chosen time, with per-kWh charges and GST); null until both
 * runs have finished and their prices are in. While it is set, savingEstimated is false.
 *
 * CSDT4-40: runMinutes (from the appliance, when known) lets the page mark both runs on the mini
 * forecast chart; remindAt is when a snoozed suggestion comes back; deadline is when it runs out.
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
        Instant decidedAt,
        Instant spikeStart,
        Instant remindAt,
        Integer confidenceCheaperDays,
        Integer confidenceDaysChecked,
        Integer confidencePercent,
        boolean savingEstimated,
        Integer runMinutes,
        Instant deadline,
        BigDecimal realisedSavingSgd) {

    public static RecommendationResponse from(Recommendation r) {
        return from(r, null);
    }

    public static RecommendationResponse from(Recommendation r, Integer runMinutes) {
        return from(r, runMinutes, null);
    }

    public static RecommendationResponse from(Recommendation r, Integer runMinutes, BigDecimal realisedSavingSgd) {
        Integer percent = r.confidenceDaysChecked() == null ? null
                : new BacktestConfidence.Confidence(r.confidenceCheaperDays(), r.confidenceDaysChecked()).percent();
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
                r.decidedAt(),
                r.spikeStart(),
                r.snoozedUntil(),
                r.confidenceCheaperDays(),
                r.confidenceDaysChecked(),
                percent,
                realisedSavingSgd == null,
                runMinutes,
                r.deadline(),
                realisedSavingSgd);
    }
}

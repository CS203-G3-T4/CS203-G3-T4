package sg.edu.smu.cs203.recommendation;

import java.time.Instant;
import java.util.List;

/**
 * A household's open suggestions, best saving first (at most 3), plus the outcome of the latest
 * background recompute. forecastSource/forecastModel are null when no forecast was used
 * (fixed-rate household, no usable forecast, stale price feed); message then says why, in words
 * the page can show. refreshedAt is null until the first recompute has run.
 */
public record RecommendationsResponse(
        long householdId,
        String forecastSource,
        String forecastModel,
        boolean demoForecast,
        String message,
        List<RecommendationResponse> recommendations,
        Instant refreshedAt) {
}

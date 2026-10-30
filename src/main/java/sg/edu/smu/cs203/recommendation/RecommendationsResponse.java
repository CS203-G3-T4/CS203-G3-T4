package sg.edu.smu.cs203.recommendation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

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
        Instant refreshedAt,
        Map<Long,JsonNode> savingsEvidence) {
    public RecommendationsResponse(long householdId,String forecastSource,String forecastModel,boolean demoForecast,
            String message,List<RecommendationResponse> recommendations,Instant refreshedAt) {
        this(householdId,forecastSource,forecastModel,demoForecast,message,recommendations,refreshedAt,Map.of());
    }
}

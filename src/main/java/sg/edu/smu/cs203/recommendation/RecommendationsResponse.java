package sg.edu.smu.cs203.recommendation;

import java.util.List;

/**
 * A household's open suggestions, plus what the latest generate call used.
 * forecastSource/forecastModel are null when no forecast was used (listing only, fixed-rate
 * household, or no usable forecast); message then says why, in words the page can show.
 */
public record RecommendationsResponse(
        long householdId,
        String forecastSource,
        String forecastModel,
        boolean demoForecast,
        String message,
        List<RecommendationResponse> recommendations) {
}

package sg.edu.smu.cs203.recommendation;

import java.util.List;

/**
 * Tabs on the Recommendations page (CSDT4-40), as ?status= on the list endpoint.
 *
 * ACTIVE    - open suggestions, including ones hidden by "Remind me later" (shown as snoozed)
 * ACCEPTED  - accepted suggestions
 * DISMISSED - dismissed suggestions and spike warnings answered with "Got it"
 * HISTORY   - everything, including replaced (SUPERSEDED) and EXPIRED ones
 *
 * Decided tabs and History cover the last 30 days, newest first.
 */
public enum RecommendationTab {
    ACTIVE(List.of(RecommendationStatus.ACTIVE)),
    ACCEPTED(List.of(RecommendationStatus.ACCEPTED)),
    DISMISSED(List.of(RecommendationStatus.DISMISSED, RecommendationStatus.ACKNOWLEDGED)),
    HISTORY(List.of(RecommendationStatus.values()));

    static final int HISTORY_DAYS = 30;
    static final int MAX_ROWS = 200;

    private final List<RecommendationStatus> statuses;

    RecommendationTab(List<RecommendationStatus> statuses) {
        this.statuses = statuses;
    }

    public List<RecommendationStatus> statuses() {
        return statuses;
    }
}

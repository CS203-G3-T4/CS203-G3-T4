package sg.edu.smu.cs203.recommendation;

public class RecommendationNotFoundException extends RuntimeException {

    public RecommendationNotFoundException(long recommendationId) {
        super("Recommendation " + recommendationId + " does not exist");
    }
}

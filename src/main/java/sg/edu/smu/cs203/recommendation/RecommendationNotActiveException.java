package sg.edu.smu.cs203.recommendation;

/** The suggestion was already accepted, dismissed, replaced or has expired (HTTP 409). */
public class RecommendationNotActiveException extends RuntimeException {

    public RecommendationNotActiveException(String message) {
        super(message);
    }
}

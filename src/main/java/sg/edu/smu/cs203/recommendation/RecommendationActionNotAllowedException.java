package sg.edu.smu.cs203.recommendation;

/**
 * The action doesn't apply to this kind of suggestion, e.g. "Got it" on a shift suggestion or
 * "Accept" on a spike warning (CSDT4-38).
 */
public class RecommendationActionNotAllowedException extends RuntimeException {

    public RecommendationActionNotAllowedException(String message) {
        super(message);
    }
}

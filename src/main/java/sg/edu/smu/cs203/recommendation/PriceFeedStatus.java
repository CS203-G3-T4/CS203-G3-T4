package sg.edu.smu.cs203.recommendation;

/** Whether F1's live USEP feed is up to date. Recommendations from F3 are blocked while it is stale. */
public interface PriceFeedStatus {

    /** True when the latest stored price is STALE, or no price has been stored yet. */
    boolean stale();
}

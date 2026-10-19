package sg.edu.smu.cs203.recommendation;

/** Whether F1's live USEP feed is up to date. Recommendations from F3 are blocked while it is stale. */
public interface PriceFeedStatus {

    /** True when the latest stored price is STALE, or no price has been stored yet. */
    boolean stale();

    /**
     * True while an admin has switched on the simulated NEMS outage (CSDT4-26). Unlike a real stale
     * feed, this blocks every forecast, the labelled demo forecast included, so a demo shows that no
     * new suggestions are made while the feed is down.
     */
    default boolean simulatedOutage() {
        return false;
    }
}

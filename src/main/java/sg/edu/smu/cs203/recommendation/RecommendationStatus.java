package sg.edu.smu.cs203.recommendation;

/**
 * ACTIVE     - waiting for the resident to accept or dismiss it
 * ACCEPTED   - the resident agreed to run at the suggested time or their own override
 * DISMISSED  - the resident said no; not suggested again for that run
 * SUPERSEDED - replaced by a newer suggestion, or no longer valid (appliance changed)
 * EXPIRED    - the suggested start passed without a decision
 */
public enum RecommendationStatus {
    ACTIVE,
    ACCEPTED,
    DISMISSED,
    SUPERSEDED,
    EXPIRED
}

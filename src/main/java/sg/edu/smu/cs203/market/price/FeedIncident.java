package sg.edu.smu.cs203.market.price;

import java.time.Instant;

/** A period when the NEMS feed failed or served stale data; endedAt is null while it is open. */
public record FeedIncident(long id, Instant startedAt, Instant endedAt, String reason, int failedPolls) {

    public boolean open() {
        return endedAt == null;
    }
}

package sg.edu.smu.cs203.market.price;

import java.time.Instant;

public record FeedStatusResponse(
        String status,
        Instant startedAt,
        Instant finishedAt,
        Instant sourceUpdatedAt,
        String errorMessage) {
}

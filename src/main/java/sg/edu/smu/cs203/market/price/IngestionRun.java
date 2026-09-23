package sg.edu.smu.cs203.market.price;

import java.time.Instant;

public record IngestionRun(
        Instant startedAt,
        Instant finishedAt,
        IngestionStatus status,
        Instant sourceUpdatedAt,
        String errorMessage) {
}

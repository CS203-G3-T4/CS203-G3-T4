package sg.edu.smu.cs203.market.price;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

import org.springframework.stereotype.Component;

@Component
public class PriceNormalizer {

    public static final String SOURCE = "NEMS_SN_SG";
    private static final long INTERVAL_SECONDS = 30 * 60;
    private static final Instant EARLIEST_ALLOWED = Instant.parse("2000-01-01T00:00:00Z");

    public MarketPrice normalize(UsepFeedResponse feed, Instant fetchedAt) {
        if (feed.updated() == null || feed.updated() <= 0) {
            throw new IllegalArgumentException("USEP feed is missing a valid update timestamp");
        }
        if (feed.usep() == null || feed.demand() == null || feed.demand().signum() < 0) {
            throw new IllegalArgumentException("USEP feed is missing a valid price or demand");
        }

        Instant sourceUpdatedAt = Instant.ofEpochSecond(feed.updated());
        if (sourceUpdatedAt.isBefore(EARLIEST_ALLOWED)
                || sourceUpdatedAt.isAfter(fetchedAt.plusSeconds(120))) {
            throw new IllegalArgumentException("USEP feed update timestamp is outside the allowed range");
        }

        // Bucket by source update time. EMC settlement-period attribution is pending CSDT4-11.
        long intervalEpoch = Math.floorDiv(feed.updated(), INTERVAL_SECONDS) * INTERVAL_SECONDS;
        return new MarketPrice(
                SOURCE,
                Instant.ofEpochSecond(intervalEpoch),
                sourceUpdatedAt,
                fetchedAt,
                scale(feed.usep(), 4),
                scale(feed.demand(), 3),
                feed.vcp() == null ? null : scale(feed.vcp(), 4));
    }

    private BigDecimal scale(BigDecimal value, int digits) {
        return value.setScale(digits, RoundingMode.HALF_UP);
    }
}

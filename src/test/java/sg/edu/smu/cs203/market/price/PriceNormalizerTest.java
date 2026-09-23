package sg.edu.smu.cs203.market.price;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class PriceNormalizerTest {

    private final PriceNormalizer normalizer = new PriceNormalizer();

    @Test
    void groupsProviderTimestampIntoHalfHourInterval() {
        Instant updatedAt = Instant.parse("2026-09-23T06:31:00Z");
        UsepFeedResponse feed = new UsepFeedResponse(
                updatedAt.getEpochSecond(), new BigDecimal("180.81"),
                new BigDecimal("7385"), new BigDecimal("252.68"));

        MarketPrice price = normalizer.normalize(feed, updatedAt.plusSeconds(5));

        assertThat(price.intervalStart()).isEqualTo(Instant.parse("2026-09-23T06:30:00Z"));
        assertThat(price.sourceUpdatedAt()).isEqualTo(updatedAt);
        assertThat(price.usepSgdPerMwh()).isEqualByComparingTo("180.8100");
        assertThat(price.forecastDemandMw()).isEqualByComparingTo("7385.000");
    }

    @Test
    void rejectsImpossibleFutureSourceTimestamp() {
        Instant fetchedAt = Instant.parse("2026-09-23T06:31:00Z");
        UsepFeedResponse feed = new UsepFeedResponse(
                fetchedAt.plusSeconds(300).getEpochSecond(), new BigDecimal("180"),
                new BigDecimal("7000"), null);

        assertThatThrownBy(() -> normalizer.normalize(feed, fetchedAt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timestamp");
    }
}

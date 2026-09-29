package sg.edu.smu.cs203.market.price;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class PriceNormalizerTest {

    private final PriceNormalizer normalizer = new PriceNormalizer();

    @Test
    void officialEmcEvidenceUsesCurrentPeriodEvenWithDelayedCollection() {
        // EMC_RT_20260929_24_PERIODS: 11:31 SGT -> 11:30 SGT.
        Instant source = Instant.parse("2026-09-29T03:31:00Z");
        var feed = new UsepFeedResponse(source.getEpochSecond(), new BigDecimal("120.76"),
                new BigDecimal("6481"), new BigDecimal("252.68"));
        var price = normalizer.normalize(feed, source.plusSeconds(3600));
        assertThat(price.intervalStart()).isEqualTo(Instant.parse("2026-09-29T03:30:00Z"));
        assertThat(price.sourceUpdatedAt()).isEqualTo(source);
    }

    @Test
    void officialMidnightEvidenceStaysOnSameSingaporeDate() {
        Instant source = Instant.parse("2026-09-28T16:01:00Z");
        var feed = new UsepFeedResponse(source.getEpochSecond(), new BigDecimal("195.94"),
                new BigDecimal("6509"), new BigDecimal("252.68"));
        assertThat(normalizer.normalize(feed, source.plusSeconds(5)).intervalStart())
                .isEqualTo(Instant.parse("2026-09-28T16:00:00Z"));
    }

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

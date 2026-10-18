package sg.edu.smu.cs203.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import sg.edu.smu.cs203.forecast.CurrentAssessmentService;
import sg.edu.smu.cs203.forecast.ForecastService;
import sg.edu.smu.cs203.market.price.MarketHistoryRepository;
import sg.edu.smu.cs203.market.price.MarketRevision;

/** CSDT4-38: the demo's spike thresholds use F3's rule (28-day typical + k x spread, k = 3). */
class MarketHistorySpikeThresholdsTest {

    private final Instant asOf = Instant.parse("2026-09-25T16:00:00Z");   // 26 Sep 00:00 SGT
    private final Instant target = Instant.parse("2026-09-26T08:30:00Z"); // 26 Sep 16:30 SGT
    private final MarketHistoryRepository history = mock(MarketHistoryRepository.class);
    private final CurrentAssessmentService assessments = new CurrentAssessmentService(history,
            Clock.fixed(asOf, java.time.ZoneOffset.UTC), mock(ForecastService.class),
            14, new BigDecimal("3"), BigDecimal.ONE, BigDecimal.ONE);
    private final MarketHistorySpikeThresholds thresholds = new MarketHistorySpikeThresholds(history, assessments);

    @Test
    void theThresholdIsTypicalPlusThreeSpreadsForThatHalfHour() {
        // 20 earlier days at 100 SGD/MWh: typical 100, spread floored at 1, so the threshold is 103.
        when(history.asOf(asOf)).thenReturn(sameHalfHourOnPreviousDays(20, "100"));

        assertThat(thresholds.thresholds(List.of(target), asOf).get(target)).isEqualByComparingTo("103");
    }

    @Test
    void withFewerThanFourteenDaysThereIsNoThreshold() {
        when(history.asOf(asOf)).thenReturn(sameHalfHourOnPreviousDays(13, "100"));

        assertThat(thresholds.thresholds(List.of(target), asOf)).isEmpty();
    }

    private List<MarketRevision> sameHalfHourOnPreviousDays(int days, String usep) {
        List<MarketRevision> rows = new ArrayList<>();
        for (int d = days; d >= 1; d--) {
            Instant period = target.minus(Duration.ofDays(d));
            rows.add(new MarketRevision(d, "TEST", period, period, period, new BigDecimal(usep), null, null,
                    "FINAL", "VERIFIED"));
        }
        return rows;
    }
}

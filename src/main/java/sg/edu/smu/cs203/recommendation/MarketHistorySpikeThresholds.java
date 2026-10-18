package sg.edu.smu.cs203.recommendation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import sg.edu.smu.cs203.forecast.CurrentAssessmentService;
import sg.edu.smu.cs203.forecast.ForecastTypes;
import sg.edu.smu.cs203.market.price.MarketHistoryRepository;
import sg.edu.smu.cs203.market.price.MarketRevision;

/**
 * F3's spike rule applied to saved market history: the same half-hour on each of the previous
 * 28 days gives the typical price and spread, and the threshold is typical + k x spread
 * (CurrentAssessmentService.reference, configured by forecast.spike.*).
 */
public class MarketHistorySpikeThresholds implements SpikeThresholds {

    private final MarketHistoryRepository history;
    private final CurrentAssessmentService assessments;

    public MarketHistorySpikeThresholds(MarketHistoryRepository history, CurrentAssessmentService assessments) {
        this.history = history;
        this.assessments = assessments;
    }

    @Override
    public Map<Instant, BigDecimal> thresholds(List<Instant> targets, Instant asOf) {
        List<MarketRevision> rows = history.asOf(asOf);
        Map<Instant, BigDecimal> thresholds = new HashMap<>();
        if (rows.isEmpty()) {
            return thresholds;
        }
        for (Instant target : targets) {
            ForecastTypes.Reference reference = assessments.reference(rows, target, asOf);
            if (reference.available()) {
                thresholds.put(target, reference.threshold());
            }
        }
        return thresholds;
    }
}

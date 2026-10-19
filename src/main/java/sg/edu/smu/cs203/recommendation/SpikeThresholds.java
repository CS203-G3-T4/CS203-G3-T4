package sg.edu.smu.cs203.recommendation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The price above which a half-hour counts as a spike, by F3's rule (CSDT4-38). Used to mark
 * spikes in the demo forecast, so it flags the same kind of half-hours F3's forecast would.
 */
public interface SpikeThresholds {

    /**
     * Threshold (SGD/MWh) for each target half-hour, judged only on prices known before asOf.
     * A target is missing when there isn't enough history to say what is normal for it.
     */
    Map<Instant, BigDecimal> thresholds(List<Instant> targets, Instant asOf);
}

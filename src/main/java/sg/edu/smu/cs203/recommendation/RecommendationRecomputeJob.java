package sg.edu.smu.cs203.recommendation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import sg.edu.smu.cs203.common.DemoClock;
import sg.edu.smu.cs203.household.HouseholdChanged;
import sg.edu.smu.cs203.household.HouseholdDirectory;
import sg.edu.smu.cs203.household.HouseholdNotFoundException;
import sg.edu.smu.cs203.household.HouseholdResponse;
import sg.edu.smu.cs203.market.price.MarketObservationSaved;
import sg.edu.smu.cs203.forecast.ForecastSaved;

/**
 * CSDT4-37: suggestions are recomputed in the background, never on page load.
 *
 * - on startup, so a fresh app has suggestions straight away
 * - after F3 commits a saved forecast (or a new price when using the demo forecast)
 * - when a household or one of its appliances changes (HouseholdChanged, from F2)
 * - every 30 minutes, which also picks up a new F3 forecast
 *
 * Only real households are recomputed; the 100 simulated households are left alone. Automatic
 * recomputation is paused during replay so real residents are not updated at simulated time.
 * One failing household never stops the others, and a failure never breaks the request or poll.
 */
@Component
public class RecommendationRecomputeJob {

    private static final Logger LOG = LoggerFactory.getLogger(RecommendationRecomputeJob.class);

    private final RecommendationService recommendations;
    private final HouseholdDirectory households;
    private final DemoClock clock;
    private final boolean enabled;
    private final boolean usesF3;

    public RecommendationRecomputeJob(RecommendationService recommendations, HouseholdDirectory households,
                                      DemoClock clock,
                                      @Value("${recommendation.auto-recompute:true}") boolean enabled,
                                      @Value("${recommendation.forecast-source:demo}") String source) {
        this.recommendations = recommendations;
        this.households = households;
        this.clock = clock;
        this.enabled = enabled;
        this.usesF3 = "f3".equalsIgnoreCase(source.trim());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        recomputeAll("startup");
    }

    @EventListener
    public void onNewPrice(MarketObservationSaved event) {
        if (!usesF3) recomputeAll("new price");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onForecastSaved(ForecastSaved event) {
        if (!usesF3 || !"LIVE".equals(event.mode())) return;
        try {
            recomputeAll("saved forecast");
        } catch (RuntimeException failure) {
            LOG.warn("Forecast {} committed; recommendation refresh failed",event.runId(),failure);
        }
    }

    @EventListener
    public void onHouseholdChanged(HouseholdChanged event) {
        recompute(event.householdId(), "household or appliance change");
    }

    @Scheduled(cron = "${recommendation.recompute-cron:0 1,31 * * * *}", zone = "Asia/Singapore")
    public void every30Minutes() {
        recomputeAll("schedule");
    }

    /** Returns how many households were recomputed. */
    public int recomputeAll(String why) {
        if (!enabled || clock.isRunning()) {
            return 0;
        }
        int done = 0;
        for (HouseholdResponse household : households.households()) {
            if (recompute(household.id(), why)) {
                done++;
            }
        }
        return done;
    }

    boolean recompute(long householdId, String why) {
        if (!enabled || clock.isRunning()) {
            return false;
        }
        try {
            recommendations.generateInBackground(householdId);
            return true;
        } catch (HouseholdNotFoundException deleted) {
            return false; // deleted in the meantime: nothing to do
        } catch (RuntimeException failure) {
            LOG.warn("Recomputing recommendations for household {} after {} failed; previous suggestions kept",
                    householdId, why, failure);
            return false;
        }
    }
}

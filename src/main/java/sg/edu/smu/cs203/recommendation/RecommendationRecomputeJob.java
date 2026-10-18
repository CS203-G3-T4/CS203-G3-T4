package sg.edu.smu.cs203.recommendation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import sg.edu.smu.cs203.household.HouseholdChanged;
import sg.edu.smu.cs203.household.HouseholdDirectory;
import sg.edu.smu.cs203.household.HouseholdNotFoundException;
import sg.edu.smu.cs203.household.HouseholdResponse;
import sg.edu.smu.cs203.market.price.MarketObservationSaved;

/**
 * CSDT4-37: suggestions are recomputed in the background, never on page load.
 *
 * - on startup, so a fresh app has suggestions straight away
 * - when F1 saves a new price (MarketObservationSaved)
 * - when a household or one of its appliances changes (HouseholdChanged, from F2)
 * - every 30 minutes, which also picks up a new F3 forecast and moves the demo forecast along
 *
 * Only real households are recomputed; the 100 simulated households are left alone. One failing
 * household never stops the others, and a failure never breaks the request or poll that caused it.
 */
@Component
public class RecommendationRecomputeJob {

    private static final Logger LOG = LoggerFactory.getLogger(RecommendationRecomputeJob.class);

    private final RecommendationService recommendations;
    private final HouseholdDirectory households;
    private final boolean enabled;

    public RecommendationRecomputeJob(RecommendationService recommendations, HouseholdDirectory households,
                                      @Value("${recommendation.auto-recompute:true}") boolean enabled) {
        this.recommendations = recommendations;
        this.households = households;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        recomputeAll("startup");
    }

    @EventListener
    public void onNewPrice(MarketObservationSaved event) {
        recomputeAll("new price");
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
        if (!enabled) {
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
        if (!enabled) {
            return false;
        }
        try {
            recommendations.generate(householdId);
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

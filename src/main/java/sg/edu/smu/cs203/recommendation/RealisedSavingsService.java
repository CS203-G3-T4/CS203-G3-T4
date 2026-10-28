package sg.edu.smu.cs203.recommendation;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import sg.edu.smu.cs203.household.HouseholdDirectory;
import sg.edu.smu.cs203.household.HouseholdResponse;
import sg.edu.smu.cs203.household.PlanType;
import sg.edu.smu.cs203.household.spend.TariffComponent;
import sg.edu.smu.cs203.household.spend.TariffRepository;
import sg.edu.smu.cs203.market.price.AsOfMarketPrices;

/**
 * CSDT4-53: realised savings from actual prices for a household's accepted suggestions.
 *
 * An accepted suggestion counts as followed: we assume the resident ran the appliance at the time
 * they chose instead of the usual time (Wattly can't see the meter). Only accepted shift
 * suggestions count; a spike warning answered "Got it" doesn't say when the appliance ran.
 * The run size is the one saved when it was accepted, or the appliance's current power and length
 * for suggestions accepted before that was saved.
 *
 * "This week" (Monday to Sunday) and "this month" come from the injected Clock in Singapore time,
 * so in demo mode they follow the demo clock. Actual prices are F1's saved USEP as known at that
 * time, and the bill charges are CSDT4-41's tariff_component. Read only.
 */
@Service
public class RealisedSavingsService {

    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
    private static final long HALF_HOUR_SECONDS = 1800;

    static final String FOLLOWED_NOTE = "We count an accepted suggestion as followed: we assume you ran the "
            + "appliance at the time you chose instead of your usual time.";
    static final String PRICE_LINKED_NOTE = "Saved = cost at your usual time - cost at the time you chose, both "
            + "at actual market prices (USEP) plus the per-kWh network and market charges and GST. Monthly "
            + "charges are the same whenever you run, so they're left out.";
    static final String FIXED_RATE_NOTE = "This household is on a fixed-rate plan, so every time costs the same "
            + "and moving a run saves nothing.";
    static final String PENDING_NOTE = "Not counted yet: runs that haven't finished, or whose actual prices "
            + "aren't in yet.";

    private final RecommendationRepository recommendations;
    private final HouseholdDirectory households;
    private final AsOfMarketPrices prices;
    private final TariffRepository tariffs;
    private final Clock clock;

    public RealisedSavingsService(RecommendationRepository recommendations, HouseholdDirectory households,
                                  AsOfMarketPrices prices, TariffRepository tariffs, Clock clock) {
        this.recommendations = recommendations;
        this.households = households;
        this.prices = prices;
        this.tariffs = tariffs;
        this.clock = clock;
    }

    /** Totals for this week or this month and the accepted suggestions behind them. */
    public SavingsResponse savings(long householdId, SavingsPeriod period) {
        HouseholdResponse household = households.household(householdId);
        Instant now = clock.instant();
        LocalDate today = now.atZone(SINGAPORE).toLocalDate();
        LocalDate from = period.first(today);
        LocalDate to = period.last(today);
        List<AcceptedRun> runs = recommendations.findAcceptedRuns(householdId,
                from.atStartOfDay(SINGAPORE).toInstant(), to.plusDays(1).atStartOfDay(SINGAPORE).toInstant());
        Map<Long, RealisedSavingsCalculator.Outcome> outcomes = outcomes(household, runs, now);

        List<SavingsResponse.Item> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        int realised = 0;
        for (AcceptedRun run : runs) {
            RealisedSavingsCalculator.Outcome outcome = outcomes.get(run.recommendationId());
            if (outcome.state() == RealisedSavingsCalculator.State.REALISED) {
                total = total.add(outcome.savingSgd());
                realised++;
            }
            items.add(new SavingsResponse.Item(run.recommendationId(), run.applianceId(), run.applianceName(),
                    run.usualStart(), run.chosenStart(), run.powerKw(), run.runMinutes(), outcome.state(),
                    outcome.usualCostSgd(), outcome.chosenCostSgd(), outcome.savingSgd(), run.estSaving(),
                    outcome.pendingReason()));
        }

        List<String> notes = new ArrayList<>();
        notes.add(FOLLOWED_NOTE);
        notes.add(household.exposedToWholesalePrice() ? PRICE_LINKED_NOTE : FIXED_RATE_NOTE);
        if (realised < runs.size()) {
            notes.add(PENDING_NOTE);
        }
        return new SavingsResponse(householdId, period, from, to, now, total, realised, runs.size() - realised,
                items, notes);
    }

    /**
     * Realised saving (SGD) for each of these suggestions that is accepted and realised, for the
     * cards on the Accepted tab. Others are left out. The caller has already checked access.
     */
    public Map<Long, BigDecimal> realisedSavings(long householdId, List<Long> recommendationIds) {
        List<AcceptedRun> runs = recommendations.findAcceptedRuns(householdId, recommendationIds);
        if (runs.isEmpty()) {
            return Map.of();
        }
        Map<Long, BigDecimal> saved = new HashMap<>();
        outcomes(households.household(householdId), runs, clock.instant()).forEach((id, outcome) -> {
            if (outcome.state() == RealisedSavingsCalculator.State.REALISED) {
                saved.put(id, outcome.savingSgd());
            }
        });
        return saved;
    }

    private Map<Long, RealisedSavingsCalculator.Outcome> outcomes(HouseholdResponse household,
                                                                  List<AcceptedRun> runs, Instant now) {
        Map<Long, RealisedSavingsCalculator.Outcome> outcomes = new LinkedHashMap<>();
        if (runs.isEmpty()) {
            return outcomes;
        }
        if (!household.exposedToWholesalePrice()) {
            for (AcceptedRun run : runs) {
                outcomes.put(run.recommendationId(),
                        RealisedSavingsCalculator.fixedRate(run, household.fixedRateCentsPerKwh(), now));
            }
            return outcomes;
        }
        Instant earliest = runs.stream()
                .map(r -> r.usualStart().isBefore(r.chosenStart()) ? r.usualStart() : r.chosenStart())
                .min(Comparator.naturalOrder()).orElseThrow();
        Instant latest = runs.stream()
                .map(r -> (r.usualStart().isAfter(r.chosenStart()) ? r.usualStart() : r.chosenStart())
                        .plus(Duration.ofMinutes(r.runMinutes())))
                .max(Comparator.naturalOrder()).orElseThrow();
        Map<Instant, BigDecimal> actual = actualUsep(Instant.ofEpochSecond(
                Math.floorDiv(earliest.getEpochSecond(), HALF_HOUR_SECONDS) * HALF_HOUR_SECONDS), now);
        List<TariffComponent> components = tariffs.forPeriod(PlanType.PRICE_LINKED,
                earliest.atZone(SINGAPORE).toLocalDate(), latest.atZone(SINGAPORE).toLocalDate());
        for (AcceptedRun run : runs) {
            outcomes.put(run.recommendationId(), RealisedSavingsCalculator.priceLinked(run, actual, components, now));
        }
        return outcomes;
    }

    /** F1's USEP for each half-hour from `from`, as known at now (latest revision published by then). */
    private Map<Instant, BigDecimal> actualUsep(Instant from, Instant now) {
        return prices.prices(from, now);
    }
}

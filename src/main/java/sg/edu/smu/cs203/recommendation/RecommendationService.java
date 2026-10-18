package sg.edu.smu.cs203.recommendation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import sg.edu.smu.cs203.household.HouseholdDirectory;
import sg.edu.smu.cs203.household.HouseholdResponse;
import sg.edu.smu.cs203.household.ValidationFailedException;
import sg.edu.smu.cs203.household.appliance.ApplianceResponse;

/**
 * CSDT4-19 (suggest the cheapest start), CSDT4-20 (accept or dismiss) and CSDT4-37 (guards,
 * ranking, background recompute). Depends on F2 only through HouseholdDirectory, on the forecast
 * only through PriceForecastSource and on F1's feed health only through PriceFeedStatus.
 *
 * Guards (each appliance must pass all of them to get a suggestion):
 * - time window: the run must start and finish inside its window, including windows that cross
 *   midnight (CheapestStartCalculator, using F2's TimeWindow rules)
 * - past start: never a start before now
 * - disabled appliance: only enabled FLEXIBLE appliances (HouseholdDirectory.flexibleAppliances)
 * - fixed-rate household: exposedToWholesalePrice must be true
 * - stale feed: no new suggestions from F3's forecast while F1's price feed is stale
 *
 * Ranking: by expected saving against the usual start, savings below the minimum ($0.05) are
 * skipped, and at most 3 suggestions per household are open at once.
 */
@Service
public class RecommendationService {

    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    static final String FIXED_RATE_MESSAGE =
            "This household is on a fixed-rate plan, so moving appliances doesn't change its bill.";
    static final String STALE_FEED_MESSAGE =
            "No new suggestions: the live price feed is out of date, so the forecast can't be trusted right now.";
    static final String NOT_YET_MESSAGE = "Suggestions are being prepared and will appear shortly.";

    private final RecommendationRepository recommendations;
    private final HouseholdDirectory households;
    private final PriceForecastSource forecasts;
    private final PriceFeedStatus feed;
    private final Clock clock;
    private final BigDecimal minimumSaving;
    private final int maxActive;
    private final boolean staleFeedBlocksDemo;

    public RecommendationService(RecommendationRepository recommendations,
                                 HouseholdDirectory households,
                                 PriceForecastSource forecasts,
                                 PriceFeedStatus feed,
                                 Clock clock,
                                 @Value("${recommendation.minimum-saving-sgd:0.05}") BigDecimal minimumSaving,
                                 @Value("${recommendation.max-active:3}") int maxActive,
                                 @Value("${recommendation.stale-feed-blocks-demo:false}") boolean staleFeedBlocksDemo) {
        if (maxActive < 1) {
            throw new IllegalArgumentException("recommendation.max-active must be at least 1");
        }
        this.recommendations = recommendations;
        this.households = households;
        this.forecasts = forecasts;
        this.feed = feed;
        this.clock = clock;
        this.minimumSaving = minimumSaving;
        this.maxActive = maxActive;
        this.staleFeedBlocksDemo = staleFeedBlocksDemo;
    }

    /**
     * What the pages show: the open suggestions (best saving first) and the outcome of the latest
     * background recompute. Never looks at the forecast; suggestions whose start passed expire.
     */
    @Transactional
    public RecommendationsResponse list(long householdId) {
        households.household(householdId);
        expirePassed(householdId, clock.instant());
        return recommendations.findRefresh(householdId)
                .map(status -> response(householdId, status))
                .orElseGet(() -> response(householdId,
                        new RecommendationRepository.RefreshStatus(null, null, null, NOT_YET_MESSAGE)));
    }

    /**
     * Recomputes a household's suggestions against the current forecast. Called in the background
     * by RecommendationRecomputeJob (new price, appliance or plan change, every 30 minutes), not on
     * page load. An unchanged suggestion is kept, a changed one replaces the old one, and a run the
     * resident already accepted or dismissed is not suggested again.
     */
    @Transactional
    public RecommendationsResponse generate(long householdId) {
        HouseholdResponse household = households.household(householdId);
        // A background run and a manual one at the same moment would both try to insert.
        recommendations.lockHousehold(householdId);
        Instant now = clock.instant();
        expirePassed(householdId, now);

        // Guard: fixed-rate households are not exposed to USEP, so moving use saves nothing.
        if (!household.exposedToWholesalePrice()) {
            closeAll(recommendations.findActive(householdId), now);
            return finish(householdId, now, null, FIXED_RATE_MESSAGE);
        }

        PriceForecastSource.Lookup lookup = forecasts.forecast();
        if (!lookup.available()) {
            return finish(householdId, now, null, "No suggestions right now: " + lookup.unavailableReason() + ".");
        }
        PriceForecast forecast = lookup.forecast();
        // Guard: a stale live feed means F3's forecast is built on old prices. The demo forecast
        // is a labelled replay that doesn't use the live feed, so it is only blocked when configured.
        if ((!forecast.isDemo() || staleFeedBlocksDemo) && feed.stale()) {
            return finish(householdId, now, null, STALE_FEED_MESSAGE);
        }

        Map<Long, Recommendation> openByAppliance = new HashMap<>();
        for (Recommendation open : recommendations.findActive(householdId)) {
            openByAppliance.put(open.applianceId(), open);
        }

        // Guards: disabled and non-flexible appliances never reach here (flexibleAppliances);
        // the time-window and past-start guards are inside CheapestStartCalculator.
        List<Candidate> candidates = new ArrayList<>();
        for (ApplianceResponse appliance : households.flexibleAppliances(householdId)) {
            if (appliance.earliestStart() == null || appliance.mustFinishBy() == null) {
                continue; // F2 requires a window for FLEXIBLE; skip a bad row rather than fail
            }
            CheapestStartCalculator.Result result = CheapestStartCalculator.cheapestStart(
                    runOf(appliance), forecast.points(), now, minimumSaving);
            if (result.outcome() == CheapestStartCalculator.Outcome.SHIFT
                    && !recommendations.decidedForRun(appliance.id(), result.usualStart())) {
                candidates.add(new Candidate(appliance, result));
            }
        }

        List<Candidate> ranked = rank(candidates, maxActive);
        Set<Long> kept = new HashSet<>();
        for (Candidate candidate : ranked) {
            kept.add(candidate.appliance().id());
        }
        // Anything not in the top suggestions closes first: its appliance was deleted, disabled,
        // made non-flexible, has no worthwhile saving any more, or was pushed out of the top 3.
        for (Recommendation open : openByAppliance.values()) {
            if (!kept.contains(open.applianceId())) {
                supersede(open, now);
            }
        }
        for (Candidate candidate : ranked) {
            save(householdId, candidate, openByAppliance.get(candidate.appliance().id()), forecast, now);
        }
        return finish(householdId, now, forecast, null);
    }

    /** One appliance's best shift, before ranking. */
    record Candidate(ApplianceResponse appliance, CheapestStartCalculator.Result result) {
    }

    /** Biggest saving first; ties go to the earlier start, then the lower appliance id. */
    static List<Candidate> rank(List<Candidate> candidates, int limit) {
        return candidates.stream()
                .sorted(Comparator.comparing((Candidate c) -> c.result().saving()).reversed()
                        .thenComparing(c -> c.result().bestStart())
                        .thenComparing(c -> c.appliance().id()))
                .limit(limit)
                .toList();
    }

    private void save(long householdId, Candidate candidate, Recommendation open, PriceForecast forecast,
                      Instant now) {
        CheapestStartCalculator.Result result = candidate.result();
        ApplianceResponse appliance = candidate.appliance();
        if (open != null) {
            if (open.suggestedStart().equals(result.bestStart()) && open.usualStart().equals(result.usualStart())) {
                return; // same advice as before: keep it, so the resident's view doesn't jump around
            }
            supersede(open, now);
        }
        Recommendation created = new Recommendation(null, householdId, appliance.id(), appliance.name(),
                Recommendation.SHIFT_START, result.bestStart(), result.usualStart(), result.bestCost(),
                result.usualCost(), result.saving(), reason(result, forecast), forecast.source(),
                truncate(forecast.model(), 60), RecommendationStatus.ACTIVE, null, now, null);
        long id = recommendations.insert(created);
        recommendations.logEvent(id, RecommendationRepository.CREATED, now, result.bestStart(), false);
    }

    private RecommendationsResponse finish(long householdId, Instant now, PriceForecast forecast, String message) {
        RecommendationRepository.RefreshStatus status = new RecommendationRepository.RefreshStatus(now,
                forecast == null ? null : forecast.source(),
                forecast == null ? null : truncate(forecast.model(), 60),
                truncate(message, 300));
        recommendations.saveRefresh(householdId, status);
        return response(householdId, status);
    }

    /** Accept the suggestion, optionally at the resident's own time (HH:mm) inside the same window. */
    @Transactional
    public RecommendationResponse accept(long recommendationId, AcceptRequest request) {
        Recommendation recommendation = openRecommendation(recommendationId);
        Instant now = clock.instant();
        Instant start = recommendation.suggestedStart();
        boolean overridden = false;

        if (request != null && request.startTime() != null) {
            ApplianceResponse appliance = households.flexibleAppliances(recommendation.householdId()).stream()
                    .filter(a -> a.id() == recommendation.applianceId())
                    .findFirst()
                    .orElseThrow(() -> new RecommendationNotActiveException(
                            "This appliance is no longer flexible, so its start can't be moved"));
            CheapestStartCalculator.ShiftableRun run = runOf(appliance);
            Instant wanted = CheapestStartCalculator.startInSameWindow(
                    run, recommendation.suggestedStart(), LocalTime.parse(request.startTime()));
            if (wanted == null || wanted.isBefore(now)) {
                LocalTime latest = run.mustFinishBy().minusMinutes(run.runMinutes());
                throw new ValidationFailedException(Map.of("startTime",
                        "Pick a time from " + run.earliestStart().format(HH_MM) + " to " + latest.format(HH_MM)
                                + " that hasn't passed yet"));
            }
            overridden = !wanted.equals(recommendation.suggestedStart());
            start = wanted;
        } else if (!start.isAfter(now)) {
            throw new RecommendationNotActiveException("This suggestion's start time has already passed");
        }

        close(recommendation, RecommendationStatus.ACCEPTED, now, start);
        recommendations.logEvent(recommendationId, RecommendationStatus.ACCEPTED.name(), now, start, overridden);
        return RecommendationResponse.from(recommendations.findById(recommendationId).orElseThrow());
    }

    @Transactional
    public RecommendationResponse dismiss(long recommendationId) {
        Recommendation recommendation = openRecommendation(recommendationId);
        Instant now = clock.instant();
        close(recommendation, RecommendationStatus.DISMISSED, now, null);
        recommendations.logEvent(recommendationId, RecommendationStatus.DISMISSED.name(), now, null, false);
        return RecommendationResponse.from(recommendations.findById(recommendationId).orElseThrow());
    }

    private Recommendation openRecommendation(long recommendationId) {
        Recommendation recommendation = recommendations.findById(recommendationId)
                .orElseThrow(() -> new RecommendationNotFoundException(recommendationId));
        if (recommendation.status() != RecommendationStatus.ACTIVE) {
            throw new RecommendationNotActiveException("This suggestion is already "
                    + recommendation.status().name().toLowerCase());
        }
        return recommendation;
    }

    private void close(Recommendation recommendation, RecommendationStatus status, Instant now, Instant start) {
        if (!recommendations.closeActive(recommendation.id(), status, now, start)) {
            throw new RecommendationNotActiveException("This suggestion was already decided");
        }
    }

    private void expirePassed(long householdId, Instant now) {
        for (Long expired : recommendations.expirePassed(householdId, now)) {
            recommendations.logEvent(expired, RecommendationStatus.EXPIRED.name(), now, null, false);
        }
    }

    private void supersede(Recommendation recommendation, Instant now) {
        if (recommendations.closeActive(recommendation.id(), RecommendationStatus.SUPERSEDED, now, null)) {
            recommendations.logEvent(recommendation.id(), RecommendationStatus.SUPERSEDED.name(), now, null, false);
        }
    }

    private void closeAll(List<Recommendation> open, Instant now) {
        for (Recommendation recommendation : open) {
            supersede(recommendation, now);
        }
    }

    private RecommendationsResponse response(long householdId, RecommendationRepository.RefreshStatus status) {
        List<RecommendationResponse> open = recommendations.findActive(householdId).stream()
                .map(RecommendationResponse::from)
                .toList();
        return new RecommendationsResponse(householdId, status.forecastSource(), status.forecastModel(),
                PriceForecast.DEMO.equals(status.forecastSource()), status.message(), open, status.refreshedAt());
    }

    static CheapestStartCalculator.ShiftableRun runOf(ApplianceResponse appliance) {
        return new CheapestStartCalculator.ShiftableRun(appliance.powerKw(), appliance.runMinutes(),
                appliance.earliestStart(), appliance.mustFinishBy(), appliance.usualStart());
    }

    /** e.g. "Start at 14:00 instead of 19:45 to save about $2.22 on this run ($0.35 vs $2.58)." */
    static String reason(CheapestStartCalculator.Result result, PriceForecast forecast) {
        String text = "Start at " + local(result.bestStart()) + " instead of " + local(result.usualStart())
                + " to save about $" + money(result.saving()) + " on this run ($" + money(result.bestCost())
                + " vs $" + money(result.usualCost()) + ").";
        if (forecast.isDemo()) {
            return text + " Based on a demo forecast (a replay of past prices), not a live prediction.";
        }
        return text + " Based on F3's forecast (" + forecast.model() + ").";
    }

    private static String local(Instant instant) {
        return instant.atZone(SINGAPORE).toLocalTime().format(HH_MM);
    }

    private static String money(BigDecimal sgd) {
        return sgd.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String truncate(String value, int length) {
        if (value == null || value.length() <= length) {
            return value;
        }
        return value.substring(0, length);
    }
}

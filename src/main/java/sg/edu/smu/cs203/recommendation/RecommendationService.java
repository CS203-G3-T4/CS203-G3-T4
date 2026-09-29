package sg.edu.smu.cs203.recommendation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import sg.edu.smu.cs203.household.HouseholdDirectory;
import sg.edu.smu.cs203.household.HouseholdResponse;
import sg.edu.smu.cs203.household.ValidationFailedException;
import sg.edu.smu.cs203.household.appliance.ApplianceResponse;

/**
 * CSDT4-19 (suggest the cheapest start) and CSDT4-20 (accept or dismiss). Depends on F2 only
 * through HouseholdDirectory and on the forecast only through PriceForecastSource.
 */
@Service
public class RecommendationService {

    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final RecommendationRepository recommendations;
    private final HouseholdDirectory households;
    private final PriceForecastSource forecasts;
    private final Clock clock;
    private final BigDecimal minimumSaving;

    public RecommendationService(RecommendationRepository recommendations,
                                 HouseholdDirectory households,
                                 PriceForecastSource forecasts,
                                 Clock clock,
                                 @Value("${recommendation.minimum-saving-sgd:0.01}") BigDecimal minimumSaving) {
        this.recommendations = recommendations;
        this.households = households;
        this.forecasts = forecasts;
        this.clock = clock;
        this.minimumSaving = minimumSaving;
    }

    /** Open suggestions only; does not look at the forecast. Suggestions whose start passed expire. */
    @Transactional
    public RecommendationsResponse list(long householdId) {
        households.household(householdId);
        expirePassed(householdId, clock.instant());
        return response(householdId, null, null);
    }

    /**
     * Refreshes a household's suggestions against the current forecast. Safe to call on every
     * dashboard load: an unchanged suggestion is kept, a changed one replaces the old one, and a
     * run the resident already accepted or dismissed is not suggested again.
     */
    @Transactional
    public RecommendationsResponse generate(long householdId) {
        HouseholdResponse household = households.household(householdId);
        // Two generate calls at once (page load + Refresh, two tabs) would both try to insert.
        recommendations.lockHousehold(householdId);
        Instant now = clock.instant();
        expirePassed(householdId, now);

        if (!household.exposedToWholesalePrice()) {
            // Switching to a fixed-rate plan stops shifting suggestions (F2 hand-off note).
            closeAll(recommendations.findActive(householdId), now);
            return response(householdId, null,
                    "This household is on a fixed-rate plan, so moving appliances doesn't change its bill.");
        }

        PriceForecastSource.Lookup lookup = forecasts.forecast();
        if (!lookup.available()) {
            return response(householdId, null, "No suggestions right now: " + lookup.unavailableReason() + ".");
        }
        PriceForecast forecast = lookup.forecast();

        Map<Long, Recommendation> openByAppliance = new HashMap<>();
        for (Recommendation open : recommendations.findActive(householdId)) {
            openByAppliance.put(open.applianceId(), open);
        }
        Set<Long> stillFlexible = new HashSet<>();
        for (ApplianceResponse appliance : households.flexibleAppliances(householdId)) {
            if (appliance.earliestStart() == null || appliance.mustFinishBy() == null) {
                continue; // F2 requires a window for FLEXIBLE; skip a bad row rather than fail
            }
            stillFlexible.add(appliance.id());
            refresh(householdId, appliance, Optional.ofNullable(openByAppliance.get(appliance.id())),
                    forecast, now);
        }
        // Appliances deleted, switched off or made non-flexible lose their open suggestion.
        for (Recommendation open : openByAppliance.values()) {
            if (!stillFlexible.contains(open.applianceId())) {
                supersede(open, now);
            }
        }
        return response(householdId, forecast, null);
    }

    private void refresh(long householdId, ApplianceResponse appliance, Optional<Recommendation> open,
                         PriceForecast forecast, Instant now) {
        CheapestStartCalculator.Result result = CheapestStartCalculator.cheapestStart(
                runOf(appliance), forecast.points(), now, minimumSaving);

        boolean worthSuggesting = result.outcome() == CheapestStartCalculator.Outcome.SHIFT
                && !recommendations.decidedForRun(appliance.id(), result.usualStart());
        if (!worthSuggesting) {
            open.ifPresent(r -> supersede(r, now));
            return;
        }
        if (open.isPresent()) {
            Recommendation current = open.get();
            if (current.suggestedStart().equals(result.bestStart())
                    && current.usualStart().equals(result.usualStart())) {
                return; // same advice as before: keep it, so the resident's view doesn't jump around
            }
            supersede(current, now);
        }

        Recommendation created = new Recommendation(null, householdId, appliance.id(), appliance.name(),
                Recommendation.SHIFT_START, result.bestStart(), result.usualStart(), result.bestCost(),
                result.usualCost(), result.saving(), reason(result, forecast), forecast.source(),
                truncate(forecast.model(), 60), RecommendationStatus.ACTIVE, null, now, null);
        long id = recommendations.insert(created);
        recommendations.logEvent(id, RecommendationRepository.CREATED, now, result.bestStart(), false);
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

    private RecommendationsResponse response(long householdId, PriceForecast forecast, String message) {
        List<RecommendationResponse> open = recommendations.findActive(householdId).stream()
                .map(RecommendationResponse::from)
                .toList();
        if (forecast == null) {
            return new RecommendationsResponse(householdId, null, null, false, message, open);
        }
        return new RecommendationsResponse(householdId, forecast.source(), forecast.model(),
                forecast.isDemo(), message, open);
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

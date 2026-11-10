package sg.edu.smu.cs203.recommendation;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import sg.edu.smu.cs203.household.HouseholdDirectory;
import sg.edu.smu.cs203.household.HouseholdRecommendationReader;
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
 *
 * CSDT4-38: when the forecast marks a spike during an appliance's usual run, its suggestion is an
 * AVOID warning instead (the spike-free alternative and the cost avoided). Warnings pass the same
 * guards and ranking, replace a shift suggestion for that appliance, and are answered with
 * "Got it" (ACKNOWLEDGED) or "Remind me later" (hidden for an hour, or until an hour before the
 * usual start if that is sooner).
 *
 * CSDT4-39: each suggestion explains itself with the forecast prices, times and window limit
 * (RecommendationExplanation), carries a confidence from actual past prices (BacktestConfidence,
 * option A), and its saving is labelled estimated.
 *
 * CSDT4-53: accepting saves the appliance's power and run length, so the realised saving from
 * actual prices (RealisedSavingsService) can replace the estimate on accepted cards.
 *
 * CSDT4-40: the Recommendations page lists suggestions by tab (RecommendationTab), any open
 * suggestion can be snoozed until a time the resident picks, and the page can chart the demo
 * forecast the same way it charts F3's.
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
    /** "Remind me later" hides a warning this long, or until this long before the usual start. */
    static final Duration REMIND_LATER = Duration.ofHours(1);

    private final RecommendationRepository recommendations;
    private final HouseholdDirectory households;
    private final HouseholdRecommendationReader backgroundHouseholds;
    private final SavingsMonitoringService savingsMonitoring;
    private final PriceForecastSource forecasts;
    private final PriceFeedStatus feed;
    private final Clock clock;
    private final BigDecimal minimumSaving;
    private final int maxActive;
    private final boolean staleFeedBlocksDemo;
    private PastPrices pastPrices = PastPrices.NONE;
    private RealisedSavingsService realisedSavings;

    public RecommendationService(RecommendationRepository recommendations,
                                 HouseholdDirectory households,
                                 HouseholdRecommendationReader backgroundHouseholds,
                                 SavingsMonitoringService savingsMonitoring,
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
        this.backgroundHouseholds = Objects.requireNonNull(backgroundHouseholds);
        this.savingsMonitoring = Objects.requireNonNull(savingsMonitoring);
        this.forecasts = forecasts;
        this.feed = feed;
        this.clock = clock;
        this.minimumSaving = minimumSaving;
        this.maxActive = maxActive;
        this.staleFeedBlocksDemo = staleFeedBlocksDemo;
    }

    /** Actual prices for the confidence check (CSDT4-39); without them, confidence is "not enough history". */
    @Autowired(required = false)
    public void setPastPrices(PastPrices pastPrices) {
        this.pastPrices = pastPrices;
    }

    /** Realised savings for accepted cards (CSDT4-53); without it, cards show the estimate only. */
    @Autowired(required = false)
    public void setRealisedSavings(RealisedSavingsService realisedSavings) {
        this.realisedSavings = realisedSavings;
    }

    /** Clears only the dedicated replay household's decisions before a fresh demo run. */
    @Transactional
    public void resetForReplay(long householdId) {
        recommendations.lockHousehold(householdId);
        recommendations.resetForReplay(householdId);
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
     * One tab of the Recommendations page (CSDT4-40). ACTIVE includes snoozed suggestions (each with
     * its remindAt); the other tabs cover the last 30 days, most recent first. Like list(), never
     * looks at the forecast.
     */
    @Transactional
    public RecommendationsResponse list(long householdId, RecommendationTab tab) {
        households.household(householdId);
        Instant now = clock.instant();
        expirePassed(householdId, now);
        RecommendationRepository.RefreshStatus status = recommendations.findRefresh(householdId)
                .orElseGet(() -> new RecommendationRepository.RefreshStatus(null, null, null, NOT_YET_MESSAGE));
        List<Recommendation> rows = tab == RecommendationTab.ACTIVE
                ? recommendations.findActive(householdId)
                : recommendations.findByStatuses(householdId, tab.statuses(),
                        now.minus(Duration.ofDays(RecommendationTab.HISTORY_DAYS)), RecommendationTab.MAX_ROWS);
        return response(householdId, status, rows);
    }

    /** The demo forecast behind demo suggestions, for the page's mini charts (CSDT4-40). */
    public DemoForecastResponse demoForecast() {
        PriceForecastSource.Lookup lookup = forecasts.forecast();
        if (!lookup.available() || !lookup.forecast().isDemo()) {
            throw new DemoForecastNotInUseException();
        }
        return DemoForecastResponse.from(lookup.forecast());
    }

    /**
     * Recomputes a household's suggestions against the current forecast. Called in the background
     * by the manual resident endpoint; background work uses generateInBackground instead. An
     * unchanged suggestion is kept, a changed one replaces the old one, and a run the
     * resident already accepted or dismissed is not suggested again.
     */
    @Transactional
    public RecommendationsResponse generate(long householdId) {
        HouseholdResponse household = households.household(householdId);
        // A background run and a manual one at the same moment would both try to insert.
        recommendations.lockHousehold(householdId);
        var status = generateInternal(householdId, household, () -> households.flexibleAppliances(householdId));
        return response(householdId, status);
    }

    /** Worker-only operation: no controller route and no SecurityContext changes. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void generateInBackground(long householdId) {
        recommendations.lockHousehold(householdId);
        var inputs = backgroundHouseholds.read(householdId);
        generateInternal(householdId, inputs.household(), inputs::flexibleAppliances);
    }

    private RecommendationRepository.RefreshStatus generateInternal(long householdId, HouseholdResponse household,
                                                Supplier<List<ApplianceResponse>> flexibleAppliances) {
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
        // A simulated outage (CSDT4-26) blocks both, so a demo shows suggestions stopping.
        if (feed.simulatedOutage() || ((!forecast.isDemo() || staleFeedBlocksDemo) && feed.stale())) {
            return finish(householdId, now, null, STALE_FEED_MESSAGE);
        }

        Map<Long, Recommendation> openByAppliance = new HashMap<>();
        for (Recommendation open : recommendations.findActive(householdId)) {
            openByAppliance.put(open.applianceId(), open);
        }

        // Guards: disabled and non-flexible appliances never reach here (flexibleAppliances);
        // the time-window and past-start guards are inside CheapestStartCalculator.
        List<Candidate> candidates = new ArrayList<>();
        for (ApplianceResponse appliance : flexibleAppliances.get()) {
            if (appliance.earliestStart() == null || appliance.mustFinishBy() == null) {
                continue; // F2 requires a window for FLEXIBLE; skip a bad row rather than fail
            }
            CheapestStartCalculator.Result result = CheapestStartCalculator.cheapestStart(
                    runOf(appliance), forecast.points(), now, minimumSaving);
            boolean worthShowing = switch (result.outcome()) {
                // Not again for a run the resident already decided on.
                case SHIFT -> !recommendations.decidedForRun(appliance.id(), result.usualStart());
                // Still warn if they only dismissed a shift: they'll run at the usual time.
                case AVOID -> !recommendations.movedOrAcknowledgedForRun(appliance.id(), result.usualStart());
                default -> false;
            };
            if (worthShowing) {
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
        Backtest backtest = ranked.isEmpty() ? null : backtest(forecast, now);
        for (Candidate candidate : ranked) {
            save(householdId, candidate, openByAppliance.get(candidate.appliance().id()), forecast, backtest, now);
        }
        return finish(householdId, now, forecast, null);
    }

    /**
     * Actual prices to judge confidence on, and the day to judge from. For F3 that is now; for the
     * demo forecast it is the start of the replay day, since the demo replays that day's prices.
     */
    private record Backtest(Map<Instant, BigDecimal> actualPrices, Instant asOf, LocalDate replayDay) {
    }

    private Backtest backtest(PriceForecast forecast, Instant now) {
        Instant asOf = forecast.replayDay() == null ? now : forecast.replayDay().atStartOfDay(SINGAPORE).toInstant();
        return new Backtest(pastPrices.actualPrices(asOf), asOf, forecast.replayDay());
    }

    /** Confidence for one suggestion, or null when there isn't enough price history. */
    static BacktestConfidence.Confidence confidence(CheapestStartCalculator.ShiftableRun run,
                                                    CheapestStartCalculator.Result result,
                                                    Map<Instant, BigDecimal> actualPrices, Instant asOf,
                                                    LocalDate replayDay) {
        Instant usual = result.usualStart();
        Instant suggested = result.bestStart();
        if (replayDay != null) {
            // Move both runs onto the replay day, keeping their times of day and the gap between them.
            long days = ChronoUnit.DAYS.between(usual.atZone(SINGAPORE).toLocalDate(), replayDay);
            usual = usual.plus(Duration.ofDays(days));
            suggested = suggested.plus(Duration.ofDays(days));
        }
        return BacktestConfidence.of(run, usual, suggested, actualPrices, asOf);
    }

    /** One appliance's best shift or spike warning, before ranking. */
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
                      Backtest backtest, Instant now) {
        CheapestStartCalculator.Result result = candidate.result();
        ApplianceResponse appliance = candidate.appliance();
        String kind = result.outcome() == CheapestStartCalculator.Outcome.AVOID
                ? Recommendation.AVOID : Recommendation.SHIFT_START;
        Instant snoozedUntil = null;
        if (open != null) {
            if (open.kind().equals(kind) && open.suggestedStart().equals(result.bestStart())
                    && open.usualStart().equals(result.usualStart())
                    && Objects.equals(open.spikeStart(), result.spikeStart())) {
                return; // same advice as before: keep it, so the resident's view doesn't jump around
            }
            // Newer advice about the same run keeps "Remind me later", if it still comes back in time.
            Instant newDeadline = Recommendation.deadline(Recommendation.AVOID.equals(kind), result.bestStart(),
                    result.usualStart());
            if (open.snoozedAt(now) && open.usualStart().equals(result.usualStart())
                    && open.snoozedUntil().isBefore(newDeadline)) {
                snoozedUntil = open.snoozedUntil();
            }
            supersede(open, now);
        }
        CheapestStartCalculator.ShiftableRun run = runOf(appliance);
        BacktestConfidence.Confidence confidence = confidence(run, result, backtest.actualPrices(), backtest.asOf(),
                backtest.replayDay());
        Recommendation created = new Recommendation(null, householdId, appliance.id(), appliance.name(),
                kind, result.bestStart(), result.usualStart(), result.bestCost(),
                result.usualCost(), result.saving(),
                truncate(RecommendationExplanation.explain(result, run, forecast), 600), forecast.source(),
                truncate(forecast.model(), 60), RecommendationStatus.ACTIVE, null, now, null,
                result.spikeStart(), snoozedUntil,
                confidence == null ? null : confidence.cheaperDays(),
                confidence == null ? null : confidence.daysChecked());
        long id = recommendations.insert(created);
        savingsMonitoring.remember(id,householdId,appliance,result,forecast);
        recommendations.logEvent(id, RecommendationRepository.CREATED, now, result.bestStart(), false);
    }

    private RecommendationRepository.RefreshStatus finish(long householdId, Instant now, PriceForecast forecast, String message) {
        RecommendationRepository.RefreshStatus status = new RecommendationRepository.RefreshStatus(now,
                forecast == null ? null : forecast.source(),
                forecast == null ? null : truncate(forecast.model(), 60),
                truncate(message, 300));
        recommendations.saveRefresh(householdId, status);
        return status;
    }

    /** Accept the suggestion, optionally at the resident's own time (HH:mm) inside the same window. */
    @Transactional
    public RecommendationResponse accept(long recommendationId, AcceptRequest request) {
        Recommendation recommendation = openRecommendation(recommendationId);
        requireShift(recommendation);
        Instant now = clock.instant();
        Instant start = recommendation.suggestedStart();
        boolean overridden = false;
        ApplianceResponse appliance = households.flexibleAppliances(recommendation.householdId()).stream()
                .filter(a -> a.id() == recommendation.applianceId())
                .findFirst()
                .orElse(null);

        if (request != null && request.startTime() != null) {
            if (appliance == null) {
                throw new RecommendationNotActiveException(
                        "This appliance is no longer flexible, so its start can't be moved");
            }
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
        if (appliance != null) {
            // The run agreed to, for realised savings later (CSDT4-53) even if the appliance is edited.
            recommendations.saveRun(recommendationId, appliance.powerKw(), appliance.runMinutes());
            savingsMonitoring.accepted(recommendationId,start,appliance.powerKw(),appliance.runMinutes());
        }
        recommendations.logEvent(recommendationId, RecommendationStatus.ACCEPTED.name(), now, start, overridden);
        return RecommendationResponse.from(recommendations.findById(recommendationId).orElseThrow());
    }

    @Transactional
    public RecommendationResponse dismiss(long recommendationId) {
        Recommendation recommendation = openRecommendation(recommendationId);
        requireShift(recommendation);
        Instant now = clock.instant();
        close(recommendation, RecommendationStatus.DISMISSED, now, null);
        recommendations.logEvent(recommendationId, RecommendationStatus.DISMISSED.name(), now, null, false);
        return RecommendationResponse.from(recommendations.findById(recommendationId).orElseThrow());
    }

    /** "Got it" on a spike warning (CSDT4-38): closes it; the same run is not warned about again. */
    @Transactional
    public RecommendationResponse gotIt(long recommendationId) {
        Recommendation warning = openRecommendation(recommendationId);
        requireWarning(warning);
        Instant now = clock.instant();
        close(warning, RecommendationStatus.ACKNOWLEDGED, now, null);
        recommendations.logEvent(recommendationId, RecommendationStatus.ACKNOWLEDGED.name(), now, null, false);
        return RecommendationResponse.from(recommendations.findById(recommendationId).orElseThrow());
    }

    /**
     * "Remind me later" until the time the resident picks (CSDT4-40): hides any open suggestion until
     * the next time the Singapore clock shows request.until(), which must be before it runs out
     * (Recommendation.deadline). It then comes back by itself.
     */
    @Transactional
    public RecommendationResponse snooze(long recommendationId, SnoozeRequest request) {
        Recommendation recommendation = openRecommendation(recommendationId);
        Instant now = clock.instant();
        Instant until = nextAt(LocalTime.parse(request.until()), now);
        if (!until.isBefore(recommendation.deadline())) {
            throw new ValidationFailedException(Map.of("until", "Pick a time before "
                    + RecommendationExplanation.time(recommendation.deadline()) + ", when this suggestion runs out"));
        }
        return snoozeUntil(recommendation, until, now);
    }

    /**
     * The one-tap shortcut (CSDT4-38): snooze for an hour, or until an hour before the suggestion
     * runs out if that is sooner. Not possible once that is under an hour away.
     */
    @Transactional
    public RecommendationResponse remindLater(long recommendationId) {
        Recommendation recommendation = openRecommendation(recommendationId);
        Instant now = clock.instant();
        Instant remindAt = remindAt(recommendation.deadline(), now);
        if (remindAt == null) {
            throw new RecommendationActionNotAllowedException(
                    "It's less than an hour away, so there's no time to remind you later");
        }
        return snoozeUntil(recommendation, remindAt, now);
    }

    private RecommendationResponse snoozeUntil(Recommendation recommendation, Instant until, Instant now) {
        if (!recommendations.snooze(recommendation.id(), until)) {
            throw new RecommendationNotActiveException("This suggestion was already answered");
        }
        recommendations.logEvent(recommendation.id(), "SNOOZED", now, until, false);
        return RecommendationResponse.from(recommendations.findById(recommendation.id()).orElseThrow());
    }

    /** The next instant after now when the Singapore clock shows this time (today or tomorrow). */
    static Instant nextAt(LocalTime time, Instant now) {
        ZonedDateTime today = now.atZone(SINGAPORE).toLocalDate().atTime(time).atZone(SINGAPORE);
        return today.toInstant().isAfter(now) ? today.toInstant() : today.plusDays(1).toInstant();
    }

    /** When the shortcut brings a suggestion back, or null if its deadline is too close. */
    static Instant remindAt(Instant deadline, Instant now) {
        Instant inAnHour = now.plus(REMIND_LATER);
        Instant beforeDeadline = deadline.minus(REMIND_LATER);
        Instant remindAt = inAnHour.isBefore(beforeDeadline) ? inAnHour : beforeDeadline;
        return remindAt.isAfter(now) ? remindAt : null;
    }

    private static void requireShift(Recommendation recommendation) {
        if (recommendation.isWarning()) {
            throw new RecommendationActionNotAllowedException(
                    "This is a price spike warning: answer it with Got it or Remind me later");
        }
    }

    private static void requireWarning(Recommendation recommendation) {
        if (!recommendation.isWarning()) {
            throw new RecommendationActionNotAllowedException("Got it is only for price spike warnings");
        }
    }

    private Recommendation openRecommendation(long recommendationId) {
        Recommendation recommendation = recommendations.findById(recommendationId)
                .orElseThrow(() -> new RecommendationNotFoundException(recommendationId));
        households.household(recommendation.householdId());
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

    /** The dashboard view: open suggestions, without snoozed ones ("Remind me later"). */
    private RecommendationsResponse response(long householdId, RecommendationRepository.RefreshStatus status) {
        Instant now = clock.instant();
        return response(householdId, status, recommendations.findActive(householdId).stream()
                .filter(r -> !r.snoozedAt(now)) // back once the time comes
                .toList());
    }

    private RecommendationsResponse response(long householdId, RecommendationRepository.RefreshStatus status,
                                             List<Recommendation> rows) {
        Map<Long, Integer> runMinutes = new HashMap<>();
        if (!rows.isEmpty()) {
            for (ApplianceResponse appliance : households.flexibleAppliances(householdId)) {
                runMinutes.put(appliance.id(), appliance.runMinutes());
            }
        }
        Map<Long, BigDecimal> realised = realisedSavings(householdId, rows);
        List<RecommendationResponse> shown = rows.stream()
                .map(r -> RecommendationResponse.from(r, runMinutes.get(r.applianceId()), realised.get(r.id())))
                .toList();
        return new RecommendationsResponse(householdId, status.forecastSource(), status.forecastModel(),
                PriceForecast.DEMO.equals(status.forecastSource()), status.message(), shown, status.refreshedAt(),
                savingsMonitoring.views(householdId,rows));
    }

    /** Realised savings for the accepted ones among these rows (CSDT4-53), by recommendation id. */
    private Map<Long, BigDecimal> realisedSavings(long householdId, List<Recommendation> rows) {
        List<Long> accepted = rows.stream()
                .filter(r -> r.status() == RecommendationStatus.ACCEPTED && !r.isWarning())
                .map(Recommendation::id)
                .toList();
        if (realisedSavings == null || accepted.isEmpty()) {
            return Map.of();
        }
        return realisedSavings.realisedSavings(householdId, accepted);
    }

    static CheapestStartCalculator.ShiftableRun runOf(ApplianceResponse appliance) {
        return new CheapestStartCalculator.ShiftableRun(appliance.powerKw(), appliance.runMinutes(),
                appliance.earliestStart(), appliance.mustFinishBy(), appliance.usualStart());
    }

    private static String truncate(String value, int length) {
        if (value == null || value.length() <= length) {
            return value;
        }
        return value.substring(0, length);
    }
}

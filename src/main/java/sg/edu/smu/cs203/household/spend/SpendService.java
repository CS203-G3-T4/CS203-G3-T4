package sg.edu.smu.cs203.household.spend;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.DayOfWeek;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import sg.edu.smu.cs203.household.HouseholdResponse;
import sg.edu.smu.cs203.household.HouseholdService;
import sg.edu.smu.cs203.household.PlanType;
import sg.edu.smu.cs203.household.budget.BudgetRepository;
import sg.edu.smu.cs203.household.load.LoadProfileResponse;
import sg.edu.smu.cs203.household.load.LoadProfileService;
import sg.edu.smu.cs203.market.price.MarketHistoryRepository;
import sg.edu.smu.cs203.market.price.MarketRevision;

/**
 * CSDT4-41: how much a household has spent on electricity so far this month.
 *
 * "This month" and "so far" come from the injected Clock, so in demo mode (F5 supplying a demo
 * Clock) the figure follows the demo clock. Every finished half-hour from the 1st (Singapore time)
 * is counted: F2's modelled use for that half-hour, and for price-linked plans F1's saved USEP for
 * it (as known at the clock's time) plus the charges in tariff_component and GST. Read only.
 */
@Service
public class SpendService {

    private static final Duration HALF_HOUR = Duration.ofMinutes(30);

    private final HouseholdService households;
    private final LoadProfileService loads;
    private final MarketHistoryRepository prices;
    private final TariffRepository tariffs;
    private final BudgetRepository budgets;
    private final Clock clock;

    @Autowired
    public SpendService(HouseholdService households, LoadProfileService loads, MarketHistoryRepository prices,
                        TariffRepository tariffs, BudgetRepository budgets, Clock clock) {
        this.households = households;
        this.loads = loads;
        this.prices = prices;
        this.tariffs = tariffs;
        this.budgets = budgets;
        this.clock = clock;
    }

    public SpendService(HouseholdService households, LoadProfileService loads, MarketHistoryRepository prices,
                        TariffRepository tariffs, Clock clock) {
        this(households, loads, prices, tariffs, null, clock);
    }

    public MonthToDateSpendResponse monthToDate(long householdId) {
        HouseholdResponse household = households.get(householdId);
        Instant now = clock.instant();
        ZonedDateTime local = now.atZone(SpendCalculator.SINGAPORE);
        YearMonth month = YearMonth.from(local);
        Instant monthStart = month.atDay(1).atStartOfDay(SpendCalculator.SINGAPORE).toInstant();
        int halfHoursInMonth = halfHoursIn(month);

        boolean priceLinked = household.planType() == PlanType.PRICE_LINKED;
        Map<Instant, BigDecimal> usep = priceLinked ? usep(monthStart, now) : Map.of();
        List<SpendCalculator.HalfHour> halfHours = new ArrayList<>();
        for (LocalDate day = month.atDay(1); !day.isAfter(local.toLocalDate()); day = day.plusDays(1)) {
            halfHours.addAll(finishedHalfHours(householdId, day, now, usep));
        }

        List<String> notes = new ArrayList<>();
        notes.add("Use is modelled from your home type and appliances, not read from a meter.");
        SpendCalculator.Result result;
        if (priceLinked) {
            List<TariffComponent> components = tariffs.forPeriod(PlanType.PRICE_LINKED, month.atDay(1),
                    local.toLocalDate());
            if (components.isEmpty()) {
                notes.add("No bill charges are recorded for this month, so only the market price is counted.");
            }
            result = SpendCalculator.priceLinked(halfHours, components, halfHoursInMonth);
            notes.add("Market price (USEP) for each half-hour, plus the network and market charges below and GST. "
                    + "The vesting contract debit/credit varies and isn't published per household, so it isn't "
                    + "included.");
            if (result.halfHoursWithoutPrice() > 0) {
                notes.add(result.halfHoursWithoutPrice() + " half-hours have no saved market price yet and are "
                        + "left out rather than guessed.");
            }
        } else {
            BigDecimal rate = household.fixedRateCentsPerKwh() == null ? BigDecimal.ZERO
                    : household.fixedRateCentsPerKwh();
            result = SpendCalculator.fixedRate(halfHours, rate);
            notes.add("Fixed-rate plan: every kWh at your contracted " + rate.stripTrailingZeros().toPlainString()
                    + "¢/kWh from Household Settings.");
        }

        List<MonthToDateSpendResponse.Charge> charges = result.lines().stream()
                .map(l -> new MonthToDateSpendResponse.Charge(l.code(), l.charge(), l.unit().name(), l.rate(),
                        SpendCalculator.sgd(l.amountSgd()), l.source(), l.sourceUrl()))
                .toList();
        return new MonthToDateSpendResponse(householdId, month, now, household.planType(),
                household.fixedRateCentsPerKwh(), SpendCalculator.sgd(result.totalSgd()),
                SpendCalculator.sgd(result.energySgd()), SpendCalculator.sgd(result.gstSgd()),
                result.kwhCounted().setScale(2, RoundingMode.HALF_UP),
                result.kwhWithoutPrice().setScale(2, RoundingMode.HALF_UP), result.halfHoursSoFar(),
                result.halfHoursCounted(), result.halfHoursWithoutPrice(), charges, notes);
    }

    /**
     * Spend for each day from `from` up to today (CSDT4-42), each worked out exactly like the
     * month-to-date figure: finished half-hours only, so today is the part of today so far, and a
     * half-hour without a saved price is skipped (see each result's halfHoursWithoutPrice).
     */
    public Map<LocalDate, SpendCalculator.Result> dailySpend(long householdId, LocalDate from) {
        HouseholdResponse household = households.get(householdId);
        Instant now = clock.instant();
        LocalDate today = now.atZone(SpendCalculator.SINGAPORE).toLocalDate();
        boolean priceLinked = household.planType() == PlanType.PRICE_LINKED;
        Instant fromStart = from.atStartOfDay(SpendCalculator.SINGAPORE).toInstant();
        Map<Instant, BigDecimal> usep = priceLinked ? usep(fromStart, now) : Map.of();
        List<TariffComponent> components = priceLinked
                ? tariffs.forPeriod(PlanType.PRICE_LINKED, from, today) : List.of();
        BigDecimal rate = household.fixedRateCentsPerKwh() == null ? BigDecimal.ZERO
                : household.fixedRateCentsPerKwh();
        Map<LocalDate, SpendCalculator.Result> days = new LinkedHashMap<>();
        for (LocalDate day = from; !day.isAfter(today); day = day.plusDays(1)) {
            List<SpendCalculator.HalfHour> halfHours = finishedHalfHours(householdId, day, now, usep);
            days.put(day, priceLinked
                    ? SpendCalculator.priceLinked(halfHours, components, halfHoursIn(YearMonth.from(day)))
                    : SpendCalculator.fixedRate(halfHours, rate));
        }
        return days;
    }

    public WeeklySpendResponse weekly(long householdId, YearMonth requestedMonth) {
        households.get(householdId);
        ZonedDateTime local = clock.instant().atZone(SpendCalculator.SINGAPORE);
        YearMonth month = requestedMonth == null ? YearMonth.from(local) : requestedMonth;
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();
        Map<LocalDate, SpendCalculator.Result> daily = dailySpend(householdId, first);
        BigDecimal budget = budgets == null ? null : budgets.find(householdId)
                .map(BudgetRepository.Settings::monthlyBudgetSgd).orElse(null);
        int daysInMonth = month.lengthOfMonth();
        LocalDate today = local.toLocalDate();
        List<WeeklySpendResponse.Week> weeks = new ArrayList<>();
        for (LocalDate weekStart = first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
             !weekStart.isAfter(last); weekStart = weekStart.plusWeeks(1)) {
            LocalDate from = weekStart.isBefore(first) ? first : weekStart;
            LocalDate weekEnd = weekStart.plusDays(6);
            LocalDate to = weekEnd.isAfter(last) ? last : weekEnd;
            BigDecimal spent = BigDecimal.ZERO;
            int daysWithSpend = 0;
            for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
                if (!day.isAfter(today)) {
                    SpendCalculator.Result result = daily.get(day);
                    if (result != null) {
                        spent = spent.add(result.totalSgd());
                        if (result.halfHoursSoFar() > 0) {
                            daysWithSpend++;
                        }
                    }
                }
            }
            int daysInWeek = from.until(to).getDays() + 1;
            BigDecimal pace = budget == null ? null
                    : budget.multiply(BigDecimal.valueOf(daysInWeek))
                            .divide(BigDecimal.valueOf(daysInMonth), 2, RoundingMode.HALF_UP);
            weeks.add(new WeeklySpendResponse.Week(from, to, SpendCalculator.sgd(spent), pace,
                    !today.isBefore(from) && !today.isAfter(to), daysWithSpend));
        }
        return new WeeklySpendResponse(householdId, month, clock.instant(),
                budget == null ? null : SpendCalculator.sgd(budget), weeks);
    }

    static int halfHoursIn(YearMonth month) {
        Instant start = month.atDay(1).atStartOfDay(SpendCalculator.SINGAPORE).toInstant();
        Instant end = month.plusMonths(1).atDay(1).atStartOfDay(SpendCalculator.SINGAPORE).toInstant();
        return (int) (Duration.between(start, end).toMinutes() / 30);
    }

    /** The day's half-hours that have finished by now, with modelled kWh and their USEP (if saved). */
    private List<SpendCalculator.HalfHour> finishedHalfHours(long householdId, LocalDate day, Instant now,
                                                            Map<Instant, BigDecimal> usep) {
        List<SpendCalculator.HalfHour> halfHours = new ArrayList<>();
        Instant dayStart = day.atStartOfDay(SpendCalculator.SINGAPORE).toInstant();
        if (!dayStart.plus(HALF_HOUR).isAfter(now)) {
            for (LoadProfileResponse.Slot slot : loads.profile(householdId, day).slots()) {
                Instant start = day.atTime(slot.start()).atZone(SpendCalculator.SINGAPORE).toInstant();
                if (!start.plus(HALF_HOUR).isAfter(now)) {
                    halfHours.add(new SpendCalculator.HalfHour(start, slot.totalKwh(), usep.get(start)));
                }
            }
        }
        return halfHours;
    }

    /** F1's USEP for each half-hour from monthStart, as known at now (latest revision published by then). */
    private Map<Instant, BigDecimal> usep(Instant monthStart, Instant now) {
        Map<Instant, BigDecimal> byPeriod = new HashMap<>();
        for (MarketRevision revision : prices.asOf(now, monthStart)) {
            byPeriod.put(revision.periodStart(), revision.usep());
        }
        return byPeriod;
    }
}

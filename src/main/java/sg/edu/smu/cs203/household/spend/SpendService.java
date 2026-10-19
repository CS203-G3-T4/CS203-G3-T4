package sg.edu.smu.cs203.household.spend;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import sg.edu.smu.cs203.household.HouseholdResponse;
import sg.edu.smu.cs203.household.HouseholdService;
import sg.edu.smu.cs203.household.PlanType;
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
    private final Clock clock;

    public SpendService(HouseholdService households, LoadProfileService loads, MarketHistoryRepository prices,
                        TariffRepository tariffs, Clock clock) {
        this.households = households;
        this.loads = loads;
        this.prices = prices;
        this.tariffs = tariffs;
        this.clock = clock;
    }

    public MonthToDateSpendResponse monthToDate(long householdId) {
        HouseholdResponse household = households.get(householdId);
        Instant now = clock.instant();
        ZonedDateTime local = now.atZone(SpendCalculator.SINGAPORE);
        YearMonth month = YearMonth.from(local);
        Instant monthStart = month.atDay(1).atStartOfDay(SpendCalculator.SINGAPORE).toInstant();
        Instant monthEnd = month.plusMonths(1).atDay(1).atStartOfDay(SpendCalculator.SINGAPORE).toInstant();
        int halfHoursInMonth = (int) (Duration.between(monthStart, monthEnd).toMinutes() / 30);

        boolean priceLinked = household.planType() == PlanType.PRICE_LINKED;
        Map<Instant, BigDecimal> usep = priceLinked ? usep(monthStart, now) : Map.of();
        List<SpendCalculator.HalfHour> halfHours = new ArrayList<>();
        for (LocalDate day = month.atDay(1); !day.isAfter(local.toLocalDate()); day = day.plusDays(1)) {
            LoadProfileResponse profile = loads.profile(householdId, day);
            for (LoadProfileResponse.Slot slot : profile.slots()) {
                Instant start = day.atTime(slot.start()).atZone(SpendCalculator.SINGAPORE).toInstant();
                if (start.plus(HALF_HOUR).isAfter(now)) {
                    continue; // not finished yet
                }
                halfHours.add(new SpendCalculator.HalfHour(start, slot.totalKwh(), usep.get(start)));
            }
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

    /** F1's USEP for each half-hour from monthStart, as known at now (latest revision published by then). */
    private Map<Instant, BigDecimal> usep(Instant monthStart, Instant now) {
        Map<Instant, BigDecimal> byPeriod = new HashMap<>();
        for (MarketRevision revision : prices.asOf(now, monthStart)) {
            byPeriod.put(revision.periodStart(), revision.usep());
        }
        return byPeriod;
    }
}

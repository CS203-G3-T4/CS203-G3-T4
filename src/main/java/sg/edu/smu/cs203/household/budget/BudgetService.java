package sg.edu.smu.cs203.household.budget;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import sg.edu.smu.cs203.household.HouseholdResponse;
import sg.edu.smu.cs203.household.HouseholdService;
import sg.edu.smu.cs203.household.PlanType;
import sg.edu.smu.cs203.household.load.LoadProfileService;

@Service
public class BudgetService {
    private final HouseholdService households;
    private final LoadProfileService loads;
    private final BudgetRepository budgets;
    private final Clock clock;

    public BudgetService(HouseholdService households, LoadProfileService loads,
                         BudgetRepository budgets, Clock clock) {
        this.households = households;
        this.loads = loads;
        this.budgets = budgets;
        this.clock = clock;
    }

    public BudgetResponse get(long householdId) {
        HouseholdResponse household = households.get(householdId);
        BudgetRepository.Settings settings = budgets.find(householdId)
                .orElse(new BudgetRepository.Settings(null, null));
        BigDecimal rate = household.planType() == PlanType.FIXED
                ? household.fixedRateCentsPerKwh() : settings.planningRateCentsPerKwh();
        String source = household.planType() == PlanType.FIXED ? "FIXED_PLAN"
                : rate == null ? "NOT_SET" : "USER_PLANNING_RATE";
        LocalDate today = LocalDate.now(clock);
        YearMonth month = YearMonth.from(today);
        List<BudgetResponse.Day> days = new ArrayList<>();
        BigDecimal toDateKwh = BigDecimal.ZERO;
        BigDecimal monthKwh = BigDecimal.ZERO;
        BigDecimal toDateCost = BigDecimal.ZERO;
        BigDecimal monthCost = BigDecimal.ZERO;
        for (int day = 1; day <= month.lengthOfMonth(); day++) {
            LocalDate date = month.atDay(day);
            BigDecimal kwh = loads.profile(householdId, date).totalKwh();
            BigDecimal cost = rate == null ? null : cost(kwh, rate);
            days.add(new BudgetResponse.Day(date, kwh, cost));
            monthKwh = monthKwh.add(kwh);
            if (cost != null) monthCost = monthCost.add(cost);
            if (!date.isAfter(today)) {
                toDateKwh = toDateKwh.add(kwh);
                if (cost != null) toDateCost = toDateCost.add(cost);
            }
        }
        BigDecimal budget = settings.monthlyBudgetSgd();
        return new BudgetResponse(householdId, month, today, true, source, rate,
                settings.planningRateCentsPerKwh(), budget, toDateKwh, monthKwh,
                rate == null ? null : toDateCost, rate == null ? null : monthCost,
                budget == null || rate == null ? null : budget.subtract(monthCost), days);
    }

    public BudgetResponse save(long householdId, BudgetRequest request) {
        households.requireWritable(householdId);
        budgets.save(householdId, request, clock.instant());
        return get(householdId);
    }

    private static BigDecimal cost(BigDecimal kwh, BigDecimal centsPerKwh) {
        return kwh.multiply(centsPerKwh).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }
}

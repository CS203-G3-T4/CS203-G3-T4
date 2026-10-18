package sg.edu.smu.cs203.household.budget;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

public record BudgetResponse(
        long householdId,
        YearMonth month,
        LocalDate asOf,
        boolean modelled,
        String rateSource,
        BigDecimal rateCentsPerKwh,
        BigDecimal planningRateCentsPerKwh,
        BigDecimal monthlyBudgetSgd,
        BigDecimal modelledKwhToDate,
        BigDecimal modelledKwhMonth,
        BigDecimal estimatedSpendToDateSgd,
        BigDecimal projectedSpendMonthSgd,
        BigDecimal remainingBudgetSgd,
        List<Day> days) {

    public record Day(LocalDate date, BigDecimal modelledKwh, BigDecimal estimatedCostSgd) {
    }
}

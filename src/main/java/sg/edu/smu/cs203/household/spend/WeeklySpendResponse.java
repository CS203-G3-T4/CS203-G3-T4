package sg.edu.smu.cs203.household.spend;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * Weekly spending for a calendar month. Spending is calculated by the same model as the
 * month-to-date and budget-status endpoints; budget pace is the even daily pace for the saved
 * monthly budget.
 */
public record WeeklySpendResponse(long householdId, YearMonth month, Instant asOf,
                                  BigDecimal monthlyBudgetSgd, List<Week> weeks) {

    public record Week(LocalDate from, LocalDate to, BigDecimal spentSgd, BigDecimal budgetPaceSgd,
                       boolean current, int daysWithSpend) {
    }
}

package sg.edu.smu.cs203.household.budget;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Positive;

public record BudgetRequest(
        @Positive(message = "Enter a monthly budget above zero")
        @Digits(integer = 8, fraction = 2, message = "Enter dollars and cents, up to 8 digits")
        BigDecimal monthlyBudgetSgd,
        @Positive(message = "Enter a planning rate above zero")
        @DecimalMax(value = "200", message = "Enter a rate of at most 200 cents per kWh")
        @Digits(integer = 3, fraction = 4, message = "Enter at most 4 decimal places")
        BigDecimal planningRateCentsPerKwh,
        /** CSDT4-42: over-budget alerts on or off; null keeps the saved choice (on for a new budget). */
        Boolean alertsEnabled) {

    public BudgetRequest(BigDecimal monthlyBudgetSgd, BigDecimal planningRateCentsPerKwh) {
        this(monthlyBudgetSgd, planningRateCentsPerKwh, null);
    }
}

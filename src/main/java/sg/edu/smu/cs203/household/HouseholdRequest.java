package sg.edu.smu.cs203.household;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Body for creating or replacing a household profile. */
public record HouseholdRequest(
        @NotBlank(message = "Give the household a name")
        @Size(max = 100, message = "Keep the name under 100 characters")
        String name,

        @NotNull(message = "Choose a home type")
        DwellingType dwellingType,

        @NotNull(message = "Enter the number of occupants")
        @Min(value = 1, message = "A household has at least 1 occupant")
        @Max(value = 20, message = "Enter at most 20 occupants")
        Integer occupants,

        @NotNull(message = "Choose an electricity plan")
        PlanType planType,

        @Positive(message = "The fixed rate must be above 0 cents per kWh")
        @DecimalMax(value = "200", message = "The fixed rate looks too high; enter cents per kWh, e.g. 29.5")
        BigDecimal fixedRateCentsPerKwh) {
}

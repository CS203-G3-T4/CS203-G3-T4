package sg.edu.smu.cs203.household.appliance;

import java.math.BigDecimal;
import java.time.LocalTime;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Body for adding or replacing an appliance. Times are local Singapore times in HH:mm.
 * Single-field checks live here; rules that combine fields live in {@link ApplianceRules}.
 */
public record ApplianceRequest(
        @NotBlank(message = "Give the appliance a name")
        @Size(max = 80, message = "Keep the name under 80 characters")
        String name,

        @NotNull(message = "Choose an appliance type")
        ApplianceType type,

        @NotNull(message = "Enter the power in kW")
        @Positive(message = "Power must be above 0 kW")
        @DecimalMax(value = "50", message = "Power looks too high; enter kW, e.g. 2.5 for a dryer")
        BigDecimal powerKw,

        @NotNull(message = "Enter the run time in minutes")
        @Min(value = 1, message = "Run time must be at least 1 minute")
        @Max(value = 1440, message = "Run time can be at most 1440 minutes (24 hours)")
        Integer runMinutes,

        @NotNull(message = "Choose how flexible this appliance is")
        Flexibility flexibility,

        LocalTime earliestStart,
        LocalTime mustFinishBy,
        LocalTime usualStart,

        @Min(value = 0, message = "Runs per week cannot be negative")
        @Max(value = 70, message = "Enter at most 70 runs per week")
        Integer runsPerWeek,

        Boolean enabled,

        @Size(max = 500, message = "Keep notes under 500 characters")
        String notes) {
}

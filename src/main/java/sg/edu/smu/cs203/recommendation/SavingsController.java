package sg.edu.smu.cs203.recommendation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import sg.edu.smu.cs203.household.ValidationFailedException;

@Tag(name = "F4: Recommendations")
@RestController
public class SavingsController {

    private final RealisedSavingsService service;

    public SavingsController(RealisedSavingsService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/households/{householdId}/savings")
    @Operation(summary = "Realised savings from actual prices (CSDT4-53) for this week (Monday to Sunday) or this "
            + "month: cost at the usual time minus cost at the chosen time for each accepted suggestion, which "
            + "counts as followed. Returns the total and the list behind it; runs not finished or without actual "
            + "prices yet are listed as PENDING and not counted")
    public SavingsResponse savings(@PathVariable long householdId,
                                   @RequestParam(defaultValue = "week") String period) {
        return service.savings(householdId, period(period));
    }

    private static SavingsPeriod period(String period) {
        try {
            return SavingsPeriod.valueOf(period.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new ValidationFailedException(Map.of("period", "Use week or month"));
        }
    }
}

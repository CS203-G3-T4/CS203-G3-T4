package sg.edu.smu.cs203.household.budget;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Household spending and budget")
@RestController
public class BudgetStatusController {

    private final BudgetStatusService service;

    public BudgetStatusController(BudgetStatusService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/households/{householdId}/budget-status")
    @Operation(summary = "The shared month-end projection (CSDT4-42): spend so far + average daily spend over the "
            + "last 7 full days x days left, against the budget, with whether to show the over-budget alert. "
            + "The Dashboard and the Spending page both show these figures")
    public BudgetStatusResponse status(@PathVariable long householdId) {
        return service.status(householdId);
    }
}

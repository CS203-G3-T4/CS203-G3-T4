package sg.edu.smu.cs203.household.budget;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Household spending and budget")
@RestController
public class BudgetController {
    private final BudgetService service;

    public BudgetController(BudgetService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/households/{householdId}/budget")
    @Operation(summary = "Get modelled monthly energy and spending against a saved budget")
    public BudgetResponse get(@PathVariable long householdId) {
        return service.get(householdId);
    }

    @PutMapping("/api/v1/households/{householdId}/budget")
    @Operation(summary = "Save a monthly budget and optional price-linked planning rate")
    public BudgetResponse save(@PathVariable long householdId, @Valid @RequestBody BudgetRequest request) {
        return service.save(householdId, request);
    }
}

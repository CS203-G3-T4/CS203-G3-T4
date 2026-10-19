package sg.edu.smu.cs203.household.spend;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Household spending and budget")
@RestController
public class SpendController {

    private final SpendService service;

    public SpendController(SpendService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/households/{householdId}/spend/month-to-date")
    @Operation(summary = "Spend so far this month (CSDT4-41): each finished half-hour's modelled use x that "
            + "half-hour's market price plus the bill's other charges and 9% GST (price-linked), or x the "
            + "contracted rate (fixed). Each charge lists its source. Follows the app's Clock (demo mode too)")
    public MonthToDateSpendResponse monthToDate(@PathVariable long householdId) {
        return service.monthToDate(householdId);
    }
}

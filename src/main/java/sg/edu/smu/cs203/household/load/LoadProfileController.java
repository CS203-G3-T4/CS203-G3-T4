package sg.edu.smu.cs203.household.load;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "F2: Household load")
@RestController
public class LoadProfileController {

    private final LoadProfileService service;

    public LoadProfileController(LoadProfileService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/households/{householdId}/load-profile")
    @Operation(summary = "Get a household's modelled kWh for each half-hour of a day "
            + "(defaults to today in Singapore)")
    public LoadProfileResponse loadProfile(
            @PathVariable long householdId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date) {
        return service.profile(householdId, date);
    }

    @GetMapping("/api/v1/archetypes")
    @Operation(summary = "List the load archetypes and their EMA calibration targets")
    public List<LoadArchetype> archetypes() {
        return service.archetypes();
    }
}

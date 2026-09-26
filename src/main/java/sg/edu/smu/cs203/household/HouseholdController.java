package sg.edu.smu.cs203.household;

import java.net.URI;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "F2: Households")
@RestController
@RequestMapping("/api/v1/households")
public class HouseholdController {

    private final HouseholdService service;

    public HouseholdController(HouseholdService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List households (simulated ones only when includeSimulated=true)")
    public List<HouseholdResponse> list(
            @RequestParam(defaultValue = "false") boolean includeSimulated) {
        return service.list(includeSimulated);
    }

    @GetMapping("/{householdId}")
    @Operation(summary = "Get a household's profile, plan type and whether it is exposed to USEP")
    public HouseholdResponse get(@PathVariable long householdId) {
        return service.get(householdId);
    }

    @PostMapping
    @Operation(summary = "Create a household (until sign-up exists, used for testing and demos)")
    public ResponseEntity<HouseholdResponse> create(@Valid @RequestBody HouseholdRequest request) {
        HouseholdResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/households/" + created.id()))
                .body(created);
    }

    @PutMapping("/{householdId}")
    @Operation(summary = "Update a household's name, home type, occupants and plan")
    public HouseholdResponse update(@PathVariable long householdId,
                                    @Valid @RequestBody HouseholdRequest request) {
        return service.update(householdId, request);
    }

    @DeleteMapping("/{householdId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a household and its appliances (simulated ones: 409)")
    public void delete(@PathVariable long householdId) {
        service.delete(householdId);
    }
}

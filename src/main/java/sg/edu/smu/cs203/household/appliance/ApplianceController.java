package sg.edu.smu.cs203.household.appliance;

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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "F2: Appliances")
@RestController
@RequestMapping("/api/v1/households/{householdId}/appliances")
public class ApplianceController {

    private final ApplianceService service;

    public ApplianceController(ApplianceService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List a household's appliances with their power, run time and time window")
    public List<ApplianceResponse> list(@PathVariable long householdId) {
        return service.list(householdId);
    }

    @GetMapping("/{applianceId}")
    @Operation(summary = "Get one appliance")
    public ApplianceResponse get(@PathVariable long householdId, @PathVariable long applianceId) {
        return service.get(householdId, applianceId);
    }

    @PostMapping
    @Operation(summary = "Add an appliance (power in kW, run time in minutes, times as HH:mm)")
    public ResponseEntity<ApplianceResponse> create(@PathVariable long householdId,
                                                    @Valid @RequestBody ApplianceRequest request) {
        ApplianceResponse created = service.create(householdId, request);
        URI location = URI.create(
                "/api/v1/households/" + householdId + "/appliances/" + created.id());
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{applianceId}")
    @Operation(summary = "Replace an appliance's details, including switching it on or off")
    public ApplianceResponse update(@PathVariable long householdId,
                                    @PathVariable long applianceId,
                                    @Valid @RequestBody ApplianceRequest request) {
        return service.update(householdId, applianceId, request);
    }

    @DeleteMapping("/{applianceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an appliance")
    public void delete(@PathVariable long householdId, @PathVariable long applianceId) {
        service.delete(householdId, applianceId);
    }
}

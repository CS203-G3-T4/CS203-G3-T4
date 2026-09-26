package sg.edu.smu.cs203.household.appliance;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import sg.edu.smu.cs203.household.HouseholdService;
import sg.edu.smu.cs203.household.ValidationFailedException;

@Service
public class ApplianceService {

    private static final int DEFAULT_RUNS_PER_WEEK = 7;

    private final ApplianceRepository appliances;
    private final HouseholdService households;
    private final Clock clock;

    public ApplianceService(ApplianceRepository appliances, HouseholdService households,
                            Clock clock) {
        this.appliances = appliances;
        this.households = households;
        this.clock = clock;
    }

    public List<ApplianceResponse> list(long householdId) {
        households.requireExists(householdId);
        List<ApplianceResponse> result = new ArrayList<>();
        for (Appliance appliance : appliances.findByHousehold(householdId)) {
            result.add(ApplianceResponse.from(appliance));
        }
        return result;
    }

    public ApplianceResponse get(long householdId, long applianceId) {
        return ApplianceResponse.from(find(householdId, applianceId));
    }

    public ApplianceResponse create(long householdId, ApplianceRequest request) {
        households.requireExists(householdId);
        checkRules(request);
        Instant now = clock.instant();
        Appliance toSave = build(null, householdId, request, now, now);
        long id = appliances.insert(toSave);
        return get(householdId, id);
    }

    public ApplianceResponse update(long householdId, long applianceId, ApplianceRequest request) {
        Appliance existing = find(householdId, applianceId);
        checkRules(request);
        Appliance updated = build(existing.id(), householdId, request, existing.createdAt(),
                clock.instant());
        if (!appliances.update(updated)) {
            throw new ApplianceNotFoundException(householdId, applianceId);
        }
        return get(householdId, applianceId);
    }

    public void delete(long householdId, long applianceId) {
        households.requireExists(householdId);
        if (!appliances.delete(householdId, applianceId)) {
            throw new ApplianceNotFoundException(householdId, applianceId);
        }
    }

    private Appliance find(long householdId, long applianceId) {
        households.requireExists(householdId);
        return appliances.findById(householdId, applianceId)
                .orElseThrow(() -> new ApplianceNotFoundException(householdId, applianceId));
    }

    private static void checkRules(ApplianceRequest request) {
        Map<String, String> errors = ApplianceRules.check(request);
        if (!errors.isEmpty()) {
            throw new ValidationFailedException(errors);
        }
    }

    private static Appliance build(Long id, long householdId, ApplianceRequest request,
                                   Instant createdAt, Instant updatedAt) {
        int runsPerWeek = DEFAULT_RUNS_PER_WEEK;
        if (request.runsPerWeek() != null) {
            runsPerWeek = request.runsPerWeek();
        }
        boolean enabled = true;
        if (request.enabled() != null) {
            enabled = request.enabled();
        }
        String notes = null;
        if (request.notes() != null && !request.notes().isBlank()) {
            notes = request.notes().trim();
        }
        return new Appliance(id, householdId, request.name().trim(), request.type(),
                request.powerKw(), request.runMinutes(), request.flexibility(),
                request.earliestStart(), request.mustFinishBy(), request.usualStart(),
                runsPerWeek, enabled, notes, createdAt, updatedAt);
    }
}

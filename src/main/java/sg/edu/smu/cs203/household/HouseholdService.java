package sg.edu.smu.cs203.household;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import sg.edu.smu.cs203.household.load.LoadArchetype;
import sg.edu.smu.cs203.household.load.LoadArchetypeRepository;

@Service
public class HouseholdService {

    private final HouseholdRepository households;
    private final LoadArchetypeRepository archetypes;
    private final Clock clock;

    public HouseholdService(HouseholdRepository households, LoadArchetypeRepository archetypes,
                            Clock clock) {
        this.households = households;
        this.archetypes = archetypes;
        this.clock = clock;
    }

    public List<HouseholdResponse> list(boolean includeSimulated) {
        List<HouseholdResponse> result = new ArrayList<>();
        for (Household household : households.findAll(includeSimulated)) {
            result.add(HouseholdResponse.from(household));
        }
        return result;
    }

    public HouseholdResponse get(long householdId) {
        return HouseholdResponse.from(find(householdId));
    }

    public HouseholdResponse create(HouseholdRequest request) {
        checkPlanRules(request);
        Instant now = clock.instant();
        Household toSave = new Household(null, request.name().trim(), request.dwellingType(),
                request.occupants(), request.planType(), fixedRateFor(request),
                archetypeIdFor(request.dwellingType()), false, now, now);
        long id = households.insert(toSave);
        return get(id);
    }

    public HouseholdResponse update(long householdId, HouseholdRequest request) {
        Household existing = find(householdId);
        checkPlanRules(request);
        Household updated = new Household(existing.id(), request.name().trim(),
                request.dwellingType(), request.occupants(), request.planType(),
                fixedRateFor(request), archetypeIdFor(request.dwellingType()),
                existing.simulated(), existing.createdAt(), clock.instant());
        if (!households.update(updated)) {
            throw new HouseholdNotFoundException(householdId);
        }
        return get(householdId);
    }

    /**
     * Deletes a household and its appliances (CSDT4-62). Simulated households belong to the
     * admin population (CSDT4-49) and are refused, so the platform numbers stay reproducible.
     */
    public void delete(long householdId) {
        Household existing = find(householdId);
        if (existing.simulated()) {
            throw new HouseholdNotDeletableException(householdId);
        }
        if (!households.delete(householdId)) {
            throw new HouseholdNotFoundException(householdId);
        }
    }

    /** Used by other F2 services (and later F4) to reject requests for unknown households. */
    public void requireExists(long householdId) {
        if (!households.exists(householdId)) {
            throw new HouseholdNotFoundException(householdId);
        }
    }

    private Household find(long householdId) {
        return households.findById(householdId)
                .orElseThrow(() -> new HouseholdNotFoundException(householdId));
    }

    private void checkPlanRules(HouseholdRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (request.planType() == PlanType.FIXED && request.fixedRateCentsPerKwh() == null) {
            errors.put("fixedRateCentsPerKwh",
                    "Enter the fixed rate in cents per kWh for a fixed-rate plan");
        }
        if (!errors.isEmpty()) {
            throw new ValidationFailedException(errors);
        }
    }

    /** Each home type has one EMA-calibrated archetype (CSDT4-29); changing home type switches it. */
    private Long archetypeIdFor(DwellingType dwellingType) {
        return archetypes.findByDwellingType(dwellingType)
                .map(LoadArchetype::id)
                .orElse(null);
    }

    /** A price-linked plan has no fixed rate, so any value sent is ignored rather than stored. */
    private static BigDecimal fixedRateFor(HouseholdRequest request) {
        if (request.planType() == PlanType.FIXED) {
            return request.fixedRateCentsPerKwh();
        }
        return null;
    }
}

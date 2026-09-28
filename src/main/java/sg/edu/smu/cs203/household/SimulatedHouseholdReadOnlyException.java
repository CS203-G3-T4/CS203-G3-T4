package sg.edu.smu.cs203.household;

/** Raised when a write would change the seeded admin population. */
public class SimulatedHouseholdReadOnlyException extends RuntimeException {

    public SimulatedHouseholdReadOnlyException(long householdId) {
        super("Household " + householdId + " is simulated and cannot be changed");
    }
}

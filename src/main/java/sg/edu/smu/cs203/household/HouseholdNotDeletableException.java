package sg.edu.smu.cs203.household;

/** Thrown when deleting a simulated household, which belongs to the admin population. */
public class HouseholdNotDeletableException extends RuntimeException {

    public HouseholdNotDeletableException(long householdId) {
        super("Household " + householdId + " is simulated and belongs to the admin view, "
                + "so it cannot be deleted");
    }
}

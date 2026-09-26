package sg.edu.smu.cs203.household;

public class HouseholdNotFoundException extends RuntimeException {

    public HouseholdNotFoundException(long householdId) {
        super("Household " + householdId + " does not exist");
    }
}

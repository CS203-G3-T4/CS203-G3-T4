package sg.edu.smu.cs203.household.appliance;

public class ApplianceNotFoundException extends RuntimeException {

    public ApplianceNotFoundException(long householdId, long applianceId) {
        super("Appliance " + applianceId + " does not exist in household " + householdId);
    }
}

package sg.edu.smu.cs203.household;

/**
 * Published after a household or one of its appliances is created or changed, so other features
 * (F4 recommendations, CSDT4-37) can react without reading F2's tables.
 */
public record HouseholdChanged(long householdId) {
}

package sg.edu.smu.cs203.household.appliance;

/**
 * FLEXIBLE     - can start any time inside [earliestStart, mustFinishBy]; Wattly may suggest moving it.
 * FIXED        - always starts at usualStart (e.g. aircon at 6 PM); never moved.
 * NOT_FLEXIBLE - runs whenever it needs to (e.g. fridge); no time window.
 */
public enum Flexibility {
    FLEXIBLE,
    FIXED,
    NOT_FLEXIBLE
}

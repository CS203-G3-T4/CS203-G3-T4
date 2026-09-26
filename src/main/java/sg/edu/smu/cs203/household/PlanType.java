package sg.edu.smu.cs203.household;

/**
 * FIXED households pay a contracted rate, so wholesale (USEP) swings don't affect them.
 * PRICE_LINKED households pay a rate that follows USEP.
 */
public enum PlanType {
    FIXED,
    PRICE_LINKED
}

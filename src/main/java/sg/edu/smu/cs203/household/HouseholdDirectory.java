package sg.edu.smu.cs203.household;

import java.time.LocalDate;
import java.util.List;

import sg.edu.smu.cs203.household.appliance.ApplianceResponse;
import sg.edu.smu.cs203.household.load.LoadProfileResponse;

/**
 * F2's hand-off to the rest of the app (agreed in CSDT4-9). F4's recommendation engine and
 * spend calculation should depend on this interface, not on F2's repositories.
 */
public interface HouseholdDirectory {

    /** Profile, plan type and whether the household is exposed to USEP. */
    HouseholdResponse household(long householdId);

    /** Enabled appliances with a flexible time window: the ones Wattly may suggest moving. */
    List<ApplianceResponse> flexibleAppliances(long householdId);

    /** Modelled kWh per half-hour for a date (null = today in Singapore). */
    LoadProfileResponse loadProfile(long householdId, LocalDate date);
}

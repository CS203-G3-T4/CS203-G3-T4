package sg.edu.smu.cs203.admin;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record AdminOverviewResponse(Instant asOf, int householdsMonitored, int priceLinkedHouseholds,
                                    BigDecimal totalSavingsThisMonthSgd, BigDecimal forecastMaeSgdPerMwh,
                                    boolean forecastAccuracyAvailable, List<HouseholdSummary> households) {

    public record HouseholdSummary(long householdId, String name, String plan, BigDecimal savingsThisMonthSgd,
                                   String status, boolean simulated) {
    }
}

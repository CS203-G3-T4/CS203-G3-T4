package sg.edu.smu.cs203.household.spend;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

import sg.edu.smu.cs203.household.PlanType;

/**
 * Month-to-date spend (CSDT4-41), in SGD rounded to cents. Use is modelled (F2), not metered.
 *
 * energySgd is the wholesale energy part (kWh x USEP) for price-linked plans, or kWh x the
 * contracted rate for fixed-rate plans. charges lists every other charge and GST with its rate,
 * unit and source. halfHoursCounted of halfHoursSoFar had a market price; the rest were skipped.
 */
public record MonthToDateSpendResponse(
        long householdId,
        YearMonth month,
        Instant asOf,
        PlanType planType,
        BigDecimal fixedRateCentsPerKwh,
        BigDecimal totalSgd,
        BigDecimal energySgd,
        BigDecimal gstSgd,
        BigDecimal kwh,
        BigDecimal kwhWithoutPrice,
        int halfHoursSoFar,
        int halfHoursCounted,
        int halfHoursWithoutPrice,
        List<Charge> charges,
        List<String> notes) {

    public record Charge(String code, String charge, String unit, BigDecimal rate, BigDecimal amountSgd,
                         String source, String sourceUrl) {
    }
}

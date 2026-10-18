package sg.edu.smu.cs203.household.spend;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * One charge on the bill besides the wholesale energy price (CSDT4-41), with where its rate comes
 * from. Rates exclude GST; GST itself is a PERCENT component.
 *
 * @param appliesFrom Singapore time of day the charge starts applying, or null for all day
 * @param appliesTo   when it stops (exclusive); a window may cross midnight (23:00 -> 07:00)
 */
public record TariffComponent(String code, String charge, Unit unit, BigDecimal rate, LocalTime appliesFrom,
                              LocalTime appliesTo, LocalDate effectiveFrom, LocalDate effectiveTo, String source,
                              String sourceUrl) {

    public enum Unit {
        /** Added to every kWh. */
        SGD_PER_KWH,
        /** A fixed monthly charge, counted in proportion to how much of the month has passed. */
        SGD_PER_MONTH,
        /** A tax on the subtotal, e.g. 9 for 9% GST. */
        PERCENT
    }

    /** True if the charge applies to a half-hour starting at this Singapore time. */
    public boolean appliesAt(LocalTime time) {
        if (appliesFrom == null) {
            return true;
        }
        if (appliesFrom.isBefore(appliesTo)) {
            return !time.isBefore(appliesFrom) && time.isBefore(appliesTo);
        }
        return !time.isBefore(appliesFrom) || time.isBefore(appliesTo); // crosses midnight
    }

    public boolean effectiveOn(LocalDate date) {
        return !date.isBefore(effectiveFrom) && (effectiveTo == null || !date.isAfter(effectiveTo));
    }
}

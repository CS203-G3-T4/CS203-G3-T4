package sg.edu.smu.cs203.recommendation;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/** The periods realised savings are totalled over (CSDT4-53), in Singapore dates. */
public enum SavingsPeriod {
    /** Monday to Sunday of the current week. */
    WEEK,
    /** The current calendar month. */
    MONTH;

    /** First day of the period that contains today. */
    public LocalDate first(LocalDate today) {
        return this == WEEK ? today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) : today.withDayOfMonth(1);
    }

    /** Last day of the period that contains today (inclusive). */
    public LocalDate last(LocalDate today) {
        return this == WEEK ? first(today).plusDays(6) : today.with(TemporalAdjusters.lastDayOfMonth());
    }
}

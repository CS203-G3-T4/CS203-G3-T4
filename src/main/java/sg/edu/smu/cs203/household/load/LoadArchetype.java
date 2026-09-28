package sg.edu.smu.cs203.household.load;

import java.math.BigDecimal;

import sg.edu.smu.cs203.household.DwellingType;

/** One calibration target: EMA's average monthly consumption for a home type. */
public record LoadArchetype(
        long id,
        String code,
        DwellingType dwellingType,
        String label,
        BigDecimal monthlyKwh,
        BigDecimal weekendFactor,
        String source) {
}

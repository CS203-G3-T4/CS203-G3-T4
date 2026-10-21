package sg.edu.smu.cs203.recommendation;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * An accepted shift suggestion, as realised savings need it (CSDT4-53). Accepting counts as
 * following it: the resident ran the appliance at chosenStart instead of usualStart.
 *
 * @param powerKw    the appliance's power when it was accepted, or its current power for older rows
 * @param runMinutes the run length, saved or current in the same way
 * @param estSaving  the forecast saving shown when it was suggested (SGD, USEP only)
 */
public record AcceptedRun(long recommendationId, long applianceId, String applianceName, Instant usualStart,
                          Instant chosenStart, BigDecimal powerKw, int runMinutes, BigDecimal estSaving) {
}

package sg.edu.smu.cs203.household.appliance;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rules that involve more than one field. Returns field name -> message; empty means valid.
 * Plain Java on purpose, so it is easy to unit-test and reuse in the recommendation engine.
 */
public final class ApplianceRules {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private ApplianceRules() {
    }

    public static Map<String, String> check(ApplianceRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (request.flexibility() == null || request.runMinutes() == null) {
            return errors; // already reported by the single-field checks
        }
        int run = request.runMinutes();

        if (request.flexibility() == Flexibility.FLEXIBLE) {
            if (request.earliestStart() == null) {
                errors.put("earliestStart", "Flexible appliances need an earliest start time");
            }
            if (request.mustFinishBy() == null) {
                errors.put("mustFinishBy", "Flexible appliances need a must-finish-by time");
            }
            if (!errors.isEmpty()) {
                return errors;
            }
            TimeWindow window = TimeWindow.of(request.earliestStart(), request.mustFinishBy());
            if (!window.fitsRunOf(run)) {
                errors.put("mustFinishBy", "This window is only " + window.lengthMinutes()
                        + " minutes long, shorter than the " + run
                        + "-minute run. Widen the window or shorten the run time.");
            } else if (request.usualStart() != null
                    && !window.allowsStartAt(request.usualStart(), run)) {
                errors.put("usualStart", "Starting at " + HH_MM.format(request.usualStart())
                        + " would not finish inside the window. The latest start is "
                        + HH_MM.format(window.latestStartFor(run)) + ".");
            }
        } else if (request.flexibility() == Flexibility.FIXED) {
            if (request.usualStart() == null) {
                errors.put("usualStart", "Fixed-time appliances need the time they start");
            }
            addNoWindowErrors(request, errors,
                    "Fixed-time appliances have no window; set the start time instead");
        } else {
            addNoWindowErrors(request, errors,
                    "Not-flexible appliances have no time window; leave it empty");
        }
        return errors;
    }

    private static void addNoWindowErrors(ApplianceRequest request, Map<String, String> errors,
                                          String message) {
        if (request.earliestStart() != null) {
            errors.put("earliestStart", message);
        }
        if (request.mustFinishBy() != null) {
            errors.put("mustFinishBy", message);
        }
    }
}

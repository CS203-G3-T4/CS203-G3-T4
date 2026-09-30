package sg.edu.smu.cs203.recommendation;

import jakarta.validation.constraints.Pattern;

/**
 * Optional body for accept. startTime is the resident's override as Singapore HH:mm, inside the
 * same window as the suggestion (e.g. "15:00"). Leave it out to accept the suggested time.
 */
public record AcceptRequest(
        @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "Use a time like 15:00")
        String startTime) {
}

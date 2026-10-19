package sg.edu.smu.cs203.recommendation;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Body for snooze (CSDT4-40): the Singapore time (HH:mm) the suggestion should come back, e.g.
 * "15:30". It means the next time the clock shows that, and must be before the suggestion runs out.
 */
public record SnoozeRequest(
        @NotNull(message = "Pick a time like 15:30")
        @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "Use a time like 15:30")
        String until) {
}

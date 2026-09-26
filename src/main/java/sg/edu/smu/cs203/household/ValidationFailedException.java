package sg.edu.smu.cs203.household;

import java.util.Map;

/**
 * Thrown for rules that involve more than one field (e.g. "the time window must be long
 * enough for the run"). The map is field name -> message, the same shape the API returns
 * for single-field @Valid errors, so the page can show every message next to its field.
 */
public class ValidationFailedException extends RuntimeException {

    private final Map<String, String> errors;

    public ValidationFailedException(Map<String, String> errors) {
        super("Request failed validation: " + errors);
        this.errors = Map.copyOf(errors);
    }

    public Map<String, String> errors() {
        return errors;
    }
}

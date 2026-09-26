package sg.edu.smu.cs203.household;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import sg.edu.smu.cs203.household.appliance.ApplianceNotFoundException;

/**
 * Error responses for the F2 endpoints only (the market endpoints keep their own handler).
 * Validation problems return 400 with an "errors" object of field name -> message.
 */
@RestControllerAdvice(basePackages = "sg.edu.smu.cs203.household")
public class HouseholdApiExceptionHandler {

    @ExceptionHandler(HouseholdNotFoundException.class)
    public ProblemDetail householdNotFound(HouseholdNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                exception.getMessage());
        problem.setTitle("Household not found");
        return problem;
    }

    @ExceptionHandler(HouseholdNotDeletableException.class)
    public ProblemDetail householdNotDeletable(HouseholdNotDeletableException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                exception.getMessage());
        problem.setTitle("Household cannot be deleted");
        return problem;
    }

    @ExceptionHandler(ApplianceNotFoundException.class)
    public ProblemDetail applianceNotFound(ApplianceNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                exception.getMessage());
        problem.setTitle("Appliance not found");
        return problem;
    }

    @ExceptionHandler(ValidationFailedException.class)
    public ProblemDetail ruleBroken(ValidationFailedException exception) {
        return invalid(exception.errors());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail fieldInvalid(MethodArgumentNotValidException exception) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            // Keep the first message per field so the page shows one clear sentence.
            errors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return invalid(errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail unreadable(HttpMessageNotReadableException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "The request body could not be read. Check that choices use the listed values "
                        + "(e.g. FLEXIBLE) and times use HH:mm (e.g. 21:00).");
        problem.setTitle("Malformed request");
        return problem;
    }

    private static ProblemDetail invalid(Map<String, String> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Some fields need fixing");
        problem.setTitle("Validation failed");
        problem.setProperty("errors", errors);
        return problem;
    }
}

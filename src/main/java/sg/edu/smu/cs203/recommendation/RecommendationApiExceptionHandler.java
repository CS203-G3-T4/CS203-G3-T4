package sg.edu.smu.cs203.recommendation;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import sg.edu.smu.cs203.household.HouseholdNotFoundException;
import sg.edu.smu.cs203.household.ValidationFailedException;

/** Error responses for the F4 endpoints, in the same shape as F2's (400 has an "errors" map). */
@RestControllerAdvice(basePackages = "sg.edu.smu.cs203.recommendation")
public class RecommendationApiExceptionHandler {

    @ExceptionHandler(HouseholdNotFoundException.class)
    public ProblemDetail householdNotFound(HouseholdNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "Household not found", exception.getMessage());
    }

    @ExceptionHandler(RecommendationNotFoundException.class)
    public ProblemDetail recommendationNotFound(RecommendationNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "Recommendation not found", exception.getMessage());
    }

    @ExceptionHandler(RecommendationNotActiveException.class)
    public ProblemDetail notActive(RecommendationNotActiveException exception) {
        return problem(HttpStatus.CONFLICT, "Recommendation is not open", exception.getMessage());
    }

    @ExceptionHandler(ValidationFailedException.class)
    public ProblemDetail ruleBroken(ValidationFailedException exception) {
        return invalid(exception.errors());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail fieldInvalid(MethodArgumentNotValidException exception) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return invalid(errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail unreadable(HttpMessageNotReadableException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Malformed request",
                "The request body could not be read. Send {\"startTime\": \"HH:mm\"} or no body.");
    }

    private static ProblemDetail invalid(Map<String, String> errors) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation failed", "Some fields need fixing");
        problem.setProperty("errors", errors);
        return problem;
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}

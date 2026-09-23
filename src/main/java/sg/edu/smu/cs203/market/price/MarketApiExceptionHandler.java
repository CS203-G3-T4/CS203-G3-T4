package sg.edu.smu.cs203.market.price;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class MarketApiExceptionHandler {

    @ExceptionHandler(NoMarketPriceException.class)
    public ProblemDetail noMarketPrice(NoMarketPriceException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        problem.setTitle("Market price unavailable");
        problem.setProperty("freshness", "UNAVAILABLE");
        return problem;
    }
}

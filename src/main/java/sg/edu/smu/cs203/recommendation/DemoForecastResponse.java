package sg.edu.smu.cs203.recommendation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The demo forecast behind demo suggestions (CSDT4-40), so the Recommendations page can chart the
 * same prices a demo suggestion was worked out from. F3 suggestions chart /api/v1/forecast/latest.
 */
public record DemoForecastResponse(String model, LocalDate replayDay, Instant asOf, List<Point> points) {

    public record Point(Instant periodStart, BigDecimal usepSgdPerMwh, boolean spike) {
    }

    static DemoForecastResponse from(PriceForecast forecast) {
        return new DemoForecastResponse(forecast.model(), forecast.replayDay(), forecast.asOf(),
                forecast.points().stream()
                        .map(p -> new Point(p.periodStart(), p.usepSgdPerMwh(), p.spike()))
                        .toList());
    }
}

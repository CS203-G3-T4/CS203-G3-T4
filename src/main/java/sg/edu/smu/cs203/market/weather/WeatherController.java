package sg.edu.smu.cs203.market.weather;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "F1: Weather")
@RestController
public class WeatherController {
    private final DailyWeatherService service;

    public WeatherController(DailyWeatherService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/weather/today")
    @Operation(summary = "Get today's Singapore forecast and heat hint, or an unavailable result")
    public DailyWeatherService.TodayResponse today() {
        return service.today();
    }
}

package sg.edu.smu.cs203.recommendation;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import sg.edu.smu.cs203.forecast.ForecastService;

/**
 * Picks the forecast behind recommendations (decision recorded in docs/f4-recommendations.md):
 *
 *   recommendation.forecast-source=demo  (default) fixed replay-day curve, labelled as a demo
 *   recommendation.forecast-source=f3    F3's saved forecast, only when it is actionable
 *
 * Set RECOMMENDATION_FORECAST_SOURCE=f3 once F3's forecast is actionable and not flat.
 */
@Configuration
public class PriceForecastConfiguration {

    @Bean
    public PriceForecastSource priceForecastSource(
            @Value("${recommendation.forecast-source:demo}") String source,
            @Value("${recommendation.demo-forecast-file:data/replay/2026-09-26_data(main_day).csv}")
            String demoFile,
            ObjectProvider<ForecastService> forecastService,
            Clock clock) {
        return switch (source.trim().toLowerCase()) {
            case "demo" -> DemoPriceForecastSource.fromClasspath(demoFile, clock);
            case "f3" -> new F3PriceForecastSource(forecastService.getObject());
            default -> throw new IllegalArgumentException(
                    "recommendation.forecast-source must be demo or f3, not '" + source + "'");
        };
    }
}

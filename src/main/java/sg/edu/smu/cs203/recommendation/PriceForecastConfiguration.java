package sg.edu.smu.cs203.recommendation;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import sg.edu.smu.cs203.forecast.CurrentAssessmentService;
import sg.edu.smu.cs203.forecast.ForecastService;
import sg.edu.smu.cs203.market.price.MarketHistoryRepository;
import sg.edu.smu.cs203.market.price.ReplayPriceCatalog;
import java.time.LocalDate;

/**
 * Picks the forecast behind recommendations (decision recorded in docs/f4-recommendations.md):
 *
 *   recommendation.forecast-source=demo  (default) fixed replay-day curve, labelled as a demo
 *   recommendation.forecast-source=f3    F3's saved forecast, only when it is actionable
 *
 * Set RECOMMENDATION_FORECAST_SOURCE=f3 once F3's forecast is actionable and not flat.
 * RECOMMENDATION_DEMO_SPIKES=false stops the demo forecast marking spikes (CSDT4-38).
 */
@Configuration
public class PriceForecastConfiguration {

    @Bean
    public PriceForecastSource priceForecastSource(
            @Value("${recommendation.forecast-source:demo}") String source,
            @Value("${recommendation.demo-replay-day:2026-09-26}") LocalDate defaultReplayDay,
            @Value("${recommendation.demo-spikes:true}") boolean demoSpikes,
            ObjectProvider<ForecastService> forecastService,
            ObjectProvider<MarketHistoryRepository> history,
            ObjectProvider<CurrentAssessmentService> assessments,
            ReplayPriceCatalog replayCatalog,
            Clock clock) {
        return switch (source.trim().toLowerCase()) {
            case "demo" -> DemoPriceForecastSource.fromCatalog(replayCatalog, defaultReplayDay, clock,
                    demoSpikes ? new MarketHistorySpikeThresholds(history.getObject(), assessments.getObject()) : null);
            case "f3" -> new F3PriceForecastSource(forecastService.getObject());
            default -> throw new IllegalArgumentException(
                    "recommendation.forecast-source must be demo or f3, not '" + source + "'");
        };
    }
}

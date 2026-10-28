package sg.edu.smu.cs203.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sg.edu.smu.cs203.forecast.ForecastJob;
import sg.edu.smu.cs203.recommendation.RecommendationService;

@Service
public class DemoReplayRefreshService {

    private static final String DEMO_HOUSEHOLD = "Replay Demo Household";

    private final JdbcTemplate jdbc;
    private final ForecastJob forecasts;
    private final RecommendationService recommendations;

    public DemoReplayRefreshService(JdbcTemplate jdbc, ForecastJob forecasts,
                                    RecommendationService recommendations) {
        this.jdbc = jdbc;
        this.forecasts = forecasts;
        this.recommendations = recommendations;
    }

    public void refresh() {
        Long householdId = demoHouseholdId();
        forecasts.runOnce();
        recommendations.generate(householdId);
    }

    @Transactional
    public void start() {
        Long householdId = demoHouseholdId();
        recommendations.resetForReplay(householdId);
        forecasts.runOnce();
        recommendations.generate(householdId);
    }

    private Long demoHouseholdId() {
        Long householdId = jdbc.queryForObject("""
                SELECT id FROM household
                WHERE name = ? AND simulated = TRUE
                """, Long.class, DEMO_HOUSEHOLD);
        if (householdId == null) {
            throw new IllegalStateException("The replay demo household has not been seeded");
        }
        return householdId;
    }
}

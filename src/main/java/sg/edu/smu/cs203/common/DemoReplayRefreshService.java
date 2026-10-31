package sg.edu.smu.cs203.common;

import java.time.Instant;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sg.edu.smu.cs203.forecast.ForecastJob;
import sg.edu.smu.cs203.market.price.ReplayPriceService;
import sg.edu.smu.cs203.recommendation.RecommendationService;

@Service
public class DemoReplayRefreshService {

    private static final String DEMO_HOUSEHOLD = "Replay Demo Household";

    private final JdbcTemplate jdbc;
    private final ReplayPriceService replayPrices;
    private final ForecastJob forecasts;
    private final RecommendationService recommendations;
    private final DemoClock clock;

    public DemoReplayRefreshService(JdbcTemplate jdbc, ReplayPriceService replayPrices, ForecastJob forecasts,
                                    RecommendationService recommendations, DemoClock clock) {
        this.jdbc = jdbc;
        this.replayPrices = replayPrices;
        this.forecasts = forecasts;
        this.recommendations = recommendations;
        this.clock = clock;
    }

    @Transactional
    public void refresh() {
        Long householdId = demoHouseholdId();
        forecasts.runOnce();
        recommendations.generate(householdId);
    }

    @Transactional
    public void start(LocalDate day, Instant start) {
        replayPrices.loadDay(day);
        clock.startAt(start);
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

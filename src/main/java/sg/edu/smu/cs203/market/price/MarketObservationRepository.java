package sg.edu.smu.cs203.market.price;

import java.sql.Timestamp;
import java.time.Duration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Repository
public class MarketObservationRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MarketHistoryRepository history;

    public MarketObservationRepository(JdbcTemplate jdbc, ObjectMapper mapper, MarketHistoryRepository history) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.history = history;
    }

    @Transactional
    public boolean save(UsepFeedResponse response, MarketPrice price, Duration staleAfter) {
        Duration age = Duration.between(price.sourceUpdatedAt(), price.fetchedAt());
        jdbc.update("""
                INSERT INTO market_observation
                    (source_updated_at, first_collected_at, last_seen_at, source_age_seconds_at_collection,
                     feed_stale_at_collection, payload)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (payload) DO UPDATE SET
                    last_seen_at = GREATEST(market_observation.last_seen_at, EXCLUDED.last_seen_at)
                """, Timestamp.from(price.sourceUpdatedAt()), Timestamp.from(price.fetchedAt()),
                Timestamp.from(price.fetchedAt()), age.toMillis() / 1000.0,
                age.compareTo(staleAfter) > 0, mapper.writeValueAsString(response));
        String payload = mapper.writeValueAsString(response);
        return history.save(new MarketRevision(0,price.source(),price.intervalStart(),price.sourceUpdatedAt(),
                price.fetchedAt(),price.usepSgdPerMwh(),price.forecastDemandMw(),price.vcpSgdPerMwh(),
                "PROVISIONAL","UNVERIFIED_FLOOR"),"app-nems",payload,payload);
    }
}

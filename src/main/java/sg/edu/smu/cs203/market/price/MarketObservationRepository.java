package sg.edu.smu.cs203.market.price;

import java.sql.Timestamp;
import java.time.Duration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class MarketObservationRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public MarketObservationRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void save(UsepFeedResponse response, MarketPrice price, Duration staleAfter) {
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
    }
}

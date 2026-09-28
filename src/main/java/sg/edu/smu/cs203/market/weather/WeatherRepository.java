package sg.edu.smu.cs203.market.weather;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Repository
public class WeatherRepository {
    private final JdbcTemplate jdbc;

    public WeatherRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void save(JsonNode items, Instant collectedAt) {
        for (JsonNode item : items) {
            Instant issued = timestamp(item, "timestamp");
            Instant updated = timestamp(item, "update_timestamp");
            Instant start = timestamp(item.path("valid_period"), "start");
            Instant end = timestamp(item.path("valid_period"), "end");
            if (!end.isAfter(start) || !item.path("forecasts").isArray()
                    || item.path("forecasts").isEmpty()) {
                throw new IllegalArgumentException("NEA forecast has no valid period or areas");
            }
            for (JsonNode forecast : item.path("forecasts")) {
                if (!forecast.path("area").isString() || forecast.path("area").asText().isBlank()
                        || !forecast.path("forecast").isString() || forecast.path("forecast").asText().isBlank()) {
                    throw new IllegalArgumentException("NEA forecast has an invalid area or forecast");
                }
            }
            jdbc.update("""
                    INSERT INTO weather_observation
                        (issued_at, updated_at, valid_start, valid_end, first_collected_at, last_seen_at, item)
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                    ON CONFLICT (issued_at, updated_at) DO UPDATE SET
                        last_seen_at = GREATEST(weather_observation.last_seen_at, EXCLUDED.last_seen_at)
                    """, Timestamp.from(issued), Timestamp.from(updated), Timestamp.from(start),
                    Timestamp.from(end), Timestamp.from(collectedAt), Timestamp.from(collectedAt), item.toString());
        }
    }

    private Instant timestamp(JsonNode node, String field) {
        return OffsetDateTime.parse(node.path(field).asText()).toInstant();
    }
}

package sg.edu.smu.cs203.market.weather;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import java.time.Instant;

@Repository
public class DailyWeatherRepository {
    private final JdbcTemplate jdbc;

    public DailyWeatherRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void save(JsonNode records, Instant fetchedAt) {
        for (JsonNode record : records) {
            DailyWeatherForecast forecast = DailyWeatherForecast.from(record, fetchedAt);
            jdbc.update("""
                    INSERT INTO daily_weather_revision(forecast_date,issued_at,updated_at,available_at,
                        valid_start,valid_end,temperature_high_c,temperature_low_c,payload)
                    VALUES (?,?,?,?,?,?,?,?,?::jsonb)
                    ON CONFLICT(forecast_date,issued_at,updated_at,payload) DO NOTHING
                    """, Date.valueOf(forecast.date()),Timestamp.from(forecast.issuedAt()),
                    Timestamp.from(forecast.updatedAt()),Timestamp.from(fetchedAt),Timestamp.from(forecast.validStart()),
                    Timestamp.from(forecast.validEnd()),forecast.temperatureHighC(),forecast.temperatureLowC(),record.toString());
            jdbc.update("""
                    INSERT INTO daily_weather
                        (forecast_date, issued_at, updated_at, valid_start, valid_end,
                         temperature_high_c, temperature_low_c, forecast, fetched_at, payload)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    ON CONFLICT (forecast_date) DO UPDATE SET
                        issued_at = EXCLUDED.issued_at, updated_at = EXCLUDED.updated_at,
                        valid_start = EXCLUDED.valid_start, valid_end = EXCLUDED.valid_end,
                        temperature_high_c = EXCLUDED.temperature_high_c,
                        temperature_low_c = EXCLUDED.temperature_low_c,
                        forecast = EXCLUDED.forecast, fetched_at = EXCLUDED.fetched_at,
                        payload = EXCLUDED.payload
                    WHERE daily_weather.updated_at <= EXCLUDED.updated_at
                    """, Date.valueOf(forecast.date()), Timestamp.from(forecast.issuedAt()),
                    Timestamp.from(forecast.updatedAt()), Timestamp.from(forecast.validStart()),
                    Timestamp.from(forecast.validEnd()), forecast.temperatureHighC(),
                    forecast.temperatureLowC(), forecast.forecast(), Timestamp.from(fetchedAt), record.toString());
        }
    }

    /**
     * The newest issue whose valid period covers {@code at} and that had been fetched by then. NEA publishes
     * each issue before its period starts (17:40 for 18:00), and the evening issue, dated the day before,
     * is the one covering the hours after midnight, so choosing by date alone shows the wrong issue.
     * {@code fetched_at} is the latest successful poll by {@code at}, which is what freshness depends on.
     */
    public Optional<DailyWeatherForecast> findCovering(Instant at) {
        Timestamp time = Timestamp.from(at);
        return jdbc.query("""
                SELECT r.forecast_date, r.issued_at, r.updated_at, r.valid_start, r.valid_end,
                       r.temperature_high_c, r.temperature_low_c,
                       r.payload->'general'->'forecast'->>'text' AS forecast,
                       GREATEST(r.available_at, COALESCE(
                           (SELECT max(d.fetched_at) FROM daily_weather d WHERE d.fetched_at <= ?),
                           r.available_at)) AS fetched_at
                FROM daily_weather_revision r
                WHERE r.valid_start <= ? AND r.valid_end > ? AND r.available_at <= ?
                ORDER BY r.updated_at DESC, r.issued_at DESC, r.id DESC
                LIMIT 1
                """, (rs, row) -> map(rs), time, time, time, time).stream().findFirst();
    }

    public Optional<DailyWeatherForecast> findByDate(LocalDate date) {
        return jdbc.query("SELECT * FROM daily_weather WHERE forecast_date = ?", (rs, row) -> map(rs),
                Date.valueOf(date)).stream().findFirst();
    }

    private static DailyWeatherForecast map(ResultSet rs) throws SQLException {
        return new DailyWeatherForecast(rs.getDate("forecast_date").toLocalDate(),
                rs.getTimestamp("issued_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getTimestamp("valid_start").toInstant(), rs.getTimestamp("valid_end").toInstant(),
                rs.getBigDecimal("temperature_high_c"), rs.getBigDecimal("temperature_low_c"),
                rs.getString("forecast"), rs.getTimestamp("fetched_at").toInstant());
    }
}

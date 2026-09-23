package sg.edu.smu.cs203.market.weather;

import java.sql.Date;
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

    public Optional<DailyWeatherForecast> findByDate(LocalDate date) {
        return jdbc.query("SELECT * FROM daily_weather WHERE forecast_date = ?", (rs, row) ->
                new DailyWeatherForecast(rs.getDate("forecast_date").toLocalDate(),
                        rs.getTimestamp("issued_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                        rs.getTimestamp("valid_start").toInstant(), rs.getTimestamp("valid_end").toInstant(),
                        rs.getBigDecimal("temperature_high_c"), rs.getBigDecimal("temperature_low_c"),
                        rs.getString("forecast"), rs.getTimestamp("fetched_at").toInstant()), Date.valueOf(date))
                .stream().findFirst();
    }
}

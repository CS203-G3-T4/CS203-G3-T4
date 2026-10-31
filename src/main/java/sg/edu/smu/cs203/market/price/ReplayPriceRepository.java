package sg.edu.smu.cs203.market.price;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ReplayPriceRepository {

    private final JdbcTemplate jdbc;

    public ReplayPriceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void replaceDay(LocalDate day, List<ReplayPriceCatalog.Row> rows) {
        jdbc.update("DELETE FROM replay_price WHERE replay_day <> ?", day);
        jdbc.update("DELETE FROM replay_price WHERE replay_day = ?", day);
        jdbc.batchUpdate("""
                INSERT INTO replay_price (replay_day, interval_start, usep_sgd_per_mwh, forecast_demand_mw)
                VALUES (?, ?, ?, ?)
                """, rows, rows.size(), (statement, row) -> {
            statement.setObject(1, row.day());
            statement.setTimestamp(2, Timestamp.from(row.intervalStart()));
            statement.setBigDecimal(3, row.usep());
            statement.setBigDecimal(4, row.demand());
        });
    }

    public Optional<MarketPrice> findLatest(Instant asOf) {
        return jdbc.query("""
                SELECT interval_start, usep_sgd_per_mwh, forecast_demand_mw
                FROM replay_price
                WHERE interval_start <= ?
                ORDER BY interval_start DESC
                LIMIT 1
                """, (rs, rowNum) -> new MarketPrice("REPLAY",
                        rs.getTimestamp("interval_start").toInstant(),
                        rs.getTimestamp("interval_start").toInstant(),
                        rs.getTimestamp("interval_start").toInstant(),
                        rs.getBigDecimal("usep_sgd_per_mwh"),
                        rs.getBigDecimal("forecast_demand_mw"), null),
                Timestamp.from(asOf)).stream().findFirst();
    }

    public List<MarketPrice> findRange(Instant from, Instant to, Instant asOf) {
        return jdbc.query("""
                SELECT interval_start, usep_sgd_per_mwh, forecast_demand_mw
                FROM replay_price
                WHERE interval_start >= ? AND interval_start < ?
                  AND interval_start <= ?
                ORDER BY interval_start ASC
                LIMIT 1500
                """, (rs, rowNum) -> new MarketPrice("REPLAY",
                rs.getTimestamp("interval_start").toInstant(),
                rs.getTimestamp("interval_start").toInstant(),
                rs.getTimestamp("interval_start").toInstant(),
                rs.getBigDecimal("usep_sgd_per_mwh"),
                rs.getBigDecimal("forecast_demand_mw"), null),
                Timestamp.from(from), Timestamp.from(to), Timestamp.from(asOf));
    }
}

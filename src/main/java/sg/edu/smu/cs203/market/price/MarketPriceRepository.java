package sg.edu.smu.cs203.market.price;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class MarketPriceRepository {

    private static final RowMapper<MarketPrice> ROW_MAPPER = (rs, rowNum) -> new MarketPrice(
            rs.getString("source"),
            rs.getTimestamp("interval_start").toInstant(),
            rs.getTimestamp("source_updated_at").toInstant(),
            rs.getTimestamp("fetched_at").toInstant(),
            rs.getBigDecimal("usep_sgd_per_mwh"),
            rs.getBigDecimal("forecast_demand_mw"),
            rs.getBigDecimal("vcp_sgd_per_mwh"));

    private final JdbcTemplate jdbc;

    public MarketPriceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(MarketPrice price) {
        jdbc.update("""
                INSERT INTO market_price
                    (source, interval_start, source_updated_at, fetched_at,
                     usep_sgd_per_mwh, forecast_demand_mw, vcp_sgd_per_mwh)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source, interval_start) DO UPDATE SET
                    source_updated_at = EXCLUDED.source_updated_at,
                    fetched_at = EXCLUDED.fetched_at,
                    usep_sgd_per_mwh = EXCLUDED.usep_sgd_per_mwh,
                    forecast_demand_mw = EXCLUDED.forecast_demand_mw,
                    vcp_sgd_per_mwh = EXCLUDED.vcp_sgd_per_mwh
                WHERE market_price.source_updated_at <= EXCLUDED.source_updated_at
                """,
                price.source(),
                Timestamp.from(price.intervalStart()),
                Timestamp.from(price.sourceUpdatedAt()),
                Timestamp.from(price.fetchedAt()),
                price.usepSgdPerMwh(),
                price.forecastDemandMw(),
                price.vcpSgdPerMwh());
    }

    public Optional<MarketPrice> findLatest() {
        List<MarketPrice> rows = jdbc.query("""
                SELECT source, interval_start, source_updated_at, fetched_at,
                       usep_sgd_per_mwh, forecast_demand_mw, vcp_sgd_per_mwh
                FROM market_price
                ORDER BY interval_start DESC, source_updated_at DESC
                LIMIT 1
                """, ROW_MAPPER);
        return rows.stream().findFirst();
    }

    public List<MarketPrice> findRange(Instant from, Instant to) {
        return jdbc.query("""
                SELECT source, interval_start, source_updated_at, fetched_at,
                       usep_sgd_per_mwh, forecast_demand_mw, vcp_sgd_per_mwh
                FROM market_price
                WHERE interval_start >= ? AND interval_start < ?
                ORDER BY interval_start ASC
                LIMIT 1500
                """, ROW_MAPPER, Timestamp.from(from), Timestamp.from(to));
    }
}

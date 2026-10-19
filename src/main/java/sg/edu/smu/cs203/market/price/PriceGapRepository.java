package sg.edu.smu.cs203.market.price;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PriceGapRepository {

    private final JdbcTemplate jdbc;

    public PriceGapRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Half-hour interval starts in [from, to) with no row in market_price, oldest first. */
    public List<Instant> findMissingIntervals(Instant from, Instant to) {
        return jdbc.query("""
                SELECT expected.interval_start
                FROM generate_series(?::timestamptz, ?::timestamptz - INTERVAL '30 minutes',
                                     INTERVAL '30 minutes') AS expected(interval_start)
                WHERE NOT EXISTS (
                    SELECT 1 FROM market_price p WHERE p.interval_start = expected.interval_start)
                ORDER BY expected.interval_start
                """, (rs, rowNum) -> rs.getTimestamp(1).toInstant(), Timestamp.from(from), Timestamp.from(to));
    }

    public void save(PriceGapCheck check) {
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO price_gap_check (checked_at, window_start, window_end, missing_intervals)
                    VALUES (?, ?, ?, ?)
                    """);
            statement.setTimestamp(1, Timestamp.from(check.checkedAt()));
            statement.setTimestamp(2, Timestamp.from(check.windowStart()));
            statement.setTimestamp(3, Timestamp.from(check.windowEnd()));
            statement.setArray(4, connection.createArrayOf("timestamptz",
                    check.missingIntervals().stream().map(Timestamp::from).toArray()));
            return statement;
        });
    }

    public Optional<PriceGapCheck> findLatest() {
        return jdbc.query("""
                SELECT checked_at, window_start, window_end, missing_intervals
                FROM price_gap_check
                ORDER BY checked_at DESC, id DESC
                LIMIT 1
                """, (rs, rowNum) -> {
            Array missing = rs.getArray("missing_intervals");
            List<Instant> intervals = Arrays.stream((Object[]) missing.getArray())
                    .map(value -> ((Timestamp) value).toInstant())
                    .sorted()
                    .toList();
            return new PriceGapCheck(rs.getTimestamp("checked_at").toInstant(),
                    rs.getTimestamp("window_start").toInstant(), rs.getTimestamp("window_end").toInstant(), intervals);
        }).stream().findFirst();
    }
}

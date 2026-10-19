package sg.edu.smu.cs203.market.price;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IngestionRunRepository {

    private final JdbcTemplate jdbc;

    public IngestionRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(IngestionRun run) {
        jdbc.update("""
                INSERT INTO ingestion_run
                    (started_at, finished_at, status, source_updated_at, error_message)
                VALUES (?, ?, ?, ?, ?)
                """,
                Timestamp.from(run.startedAt()),
                Timestamp.from(run.finishedAt()),
                run.status().name(),
                run.sourceUpdatedAt() == null ? null : Timestamp.from(run.sourceUpdatedAt()),
                run.errorMessage());
    }

    public Optional<Instant> findLastSuccessAt() {
        Timestamp at = jdbc.queryForObject(
                "SELECT max(finished_at) FROM ingestion_run WHERE status = 'SUCCESS'", Timestamp.class);
        return Optional.ofNullable(at).map(Timestamp::toInstant);
    }

    public Optional<Instant> findFirstStartedAt() {
        Timestamp at = jdbc.queryForObject("SELECT min(started_at) FROM ingestion_run", Timestamp.class);
        return Optional.ofNullable(at).map(Timestamp::toInstant);
    }

    /** Polls since the most recent SUCCESS (all polls if none succeeded). Read from the table so restarts don't reset it. */
    public int countConsecutiveFailures() {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM ingestion_run
                WHERE id > COALESCE((SELECT max(id) FROM ingestion_run WHERE status = 'SUCCESS'), 0)
                """, Integer.class);
        return count == null ? 0 : count;
    }

    /** Polls that did not return a fresh price (FAILURE or STALE_SOURCE) started in [from, to). */
    public int countFallbacks(Instant from, Instant to) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM ingestion_run
                WHERE status <> 'SUCCESS' AND started_at >= ? AND started_at < ?
                """, Integer.class, Timestamp.from(from), Timestamp.from(to));
        return count == null ? 0 : count;
    }

    public Optional<IngestionRun> findLatest() {
        List<IngestionRun> rows = jdbc.query("""
                SELECT started_at, finished_at, status, source_updated_at, error_message
                FROM ingestion_run
                ORDER BY id DESC
                LIMIT 1
                """, (rs, rowNum) -> {
            Timestamp updated = rs.getTimestamp("source_updated_at");
            return new IngestionRun(
                    rs.getTimestamp("started_at").toInstant(),
                    rs.getTimestamp("finished_at").toInstant(),
                    IngestionStatus.valueOf(rs.getString("status")),
                    updated == null ? null : updated.toInstant(),
                    rs.getString("error_message"));
        });
        return rows.stream().findFirst();
    }
}

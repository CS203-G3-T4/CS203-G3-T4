package sg.edu.smu.cs203.market.price;

import java.sql.Timestamp;
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

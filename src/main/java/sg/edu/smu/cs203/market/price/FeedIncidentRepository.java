package sg.edu.smu.cs203.market.price;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FeedIncidentRepository {

    private static final String COLUMNS = "id, started_at, ended_at, reason, failed_polls";

    private final JdbcTemplate jdbc;

    public FeedIncidentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<FeedIncident> findOpen() {
        return jdbc.query("SELECT " + COLUMNS + " FROM feed_incident WHERE ended_at IS NULL",
                FeedIncidentRepository::map).stream().findFirst();
    }

    /** Opens an incident unless one is already open; returns whether a row was inserted. */
    public boolean open(Instant startedAt, String reason) {
        return jdbc.update("""
                INSERT INTO feed_incident (started_at, reason)
                SELECT ?, ?
                WHERE NOT EXISTS (SELECT 1 FROM feed_incident WHERE ended_at IS NULL)
                """, Timestamp.from(startedAt), truncate(reason)) == 1;
    }

    public void countFailedPoll() {
        jdbc.update("UPDATE feed_incident SET failed_polls = failed_polls + 1 WHERE ended_at IS NULL");
    }

    /** Closes the open incident, if any; returns whether one was closed. */
    public boolean closeOpen(Instant endedAt) {
        return jdbc.update("""
                UPDATE feed_incident SET ended_at = GREATEST(started_at, ?)
                WHERE ended_at IS NULL
                """, Timestamp.from(endedAt)) == 1;
    }

    /** Incidents that overlap [from, to), oldest first. */
    public List<FeedIncident> findOverlapping(Instant from, Instant to) {
        return jdbc.query("SELECT " + COLUMNS + """
                 FROM feed_incident
                WHERE started_at < ? AND (ended_at IS NULL OR ended_at > ?)
                ORDER BY started_at, id
                """, FeedIncidentRepository::map, Timestamp.from(to), Timestamp.from(from));
    }

    public List<FeedIncident> findRecent(int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM feed_incident ORDER BY started_at DESC, id DESC LIMIT ?",
                FeedIncidentRepository::map, limit);
    }

    private static FeedIncident map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp ended = rs.getTimestamp("ended_at");
        return new FeedIncident(
                rs.getLong("id"),
                rs.getTimestamp("started_at").toInstant(),
                ended == null ? null : ended.toInstant(),
                rs.getString("reason"),
                rs.getInt("failed_polls"));
    }

    private static String truncate(String reason) {
        return reason.length() <= 1000 ? reason : reason.substring(0, 1000);
    }
}

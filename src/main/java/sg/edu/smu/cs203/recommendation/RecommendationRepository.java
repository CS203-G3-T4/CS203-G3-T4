package sg.edu.smu.cs203.recommendation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class RecommendationRepository {

    /** The event_type written when a suggestion is first saved. */
    static final String CREATED = "CREATED";

    private static final String SELECT = """
            SELECT r.id, r.household_id, r.appliance_id, a.name AS appliance_name, r.kind,
                   r.suggested_start, r.usual_start, r.suggested_cost, r.usual_cost, r.est_saving,
                   r.reason, r.forecast_source, r.forecast_model, r.status, r.accepted_start,
                   r.created_at, r.decided_at
            FROM recommendation r
            JOIN appliance a ON a.id = r.appliance_id
            """;

    private static final RowMapper<Recommendation> ROW_MAPPER = (rs, rowNum) -> new Recommendation(
            rs.getLong("id"),
            rs.getLong("household_id"),
            rs.getLong("appliance_id"),
            rs.getString("appliance_name"),
            rs.getString("kind"),
            instant(rs, "suggested_start"),
            instant(rs, "usual_start"),
            rs.getBigDecimal("suggested_cost"),
            rs.getBigDecimal("usual_cost"),
            rs.getBigDecimal("est_saving"),
            rs.getString("reason"),
            rs.getString("forecast_source"),
            rs.getString("forecast_model"),
            RecommendationStatus.valueOf(rs.getString("status")),
            instant(rs, "accepted_start"),
            instant(rs, "created_at"),
            instant(rs, "decided_at"));

    private final JdbcTemplate jdbc;

    public RecommendationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Holds a per-household lock until the transaction ends, so two generate calls for the same
     * household run one after the other instead of both inserting a suggestion.
     */
    public void lockHousehold(long householdId) {
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(4, ?)) AS locked",
                Integer.class, Math.toIntExact(householdId));
    }

    public Optional<Recommendation> findById(long id) {
        return jdbc.query(SELECT + " WHERE r.id = ?", ROW_MAPPER, id).stream().findFirst();
    }

    /** Open suggestions for a household, soonest first. */
    public List<Recommendation> findActive(long householdId) {
        return jdbc.query(SELECT + " WHERE r.household_id = ? AND r.status = 'ACTIVE' ORDER BY r.suggested_start, r.id",
                ROW_MAPPER, householdId);
    }

    /** True if the resident already accepted or dismissed a suggestion for this run. */
    public boolean decidedForRun(long applianceId, Instant usualStart) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM recommendation
                WHERE appliance_id = ? AND usual_start = ? AND status IN ('ACCEPTED', 'DISMISSED')
                """, Integer.class, applianceId, Timestamp.from(usualStart));
        return count != null && count > 0;
    }

    public long insert(Recommendation recommendation) {
        Long id = jdbc.queryForObject("""
                INSERT INTO recommendation
                    (household_id, appliance_id, kind, suggested_start, usual_start, suggested_cost,
                     usual_cost, est_saving, reason, forecast_source, forecast_model, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                recommendation.householdId(),
                recommendation.applianceId(),
                recommendation.kind(),
                Timestamp.from(recommendation.suggestedStart()),
                Timestamp.from(recommendation.usualStart()),
                recommendation.suggestedCost(),
                recommendation.usualCost(),
                recommendation.estSaving(),
                recommendation.reason(),
                recommendation.forecastSource(),
                recommendation.forecastModel(),
                recommendation.status().name(),
                Timestamp.from(recommendation.createdAt()));
        return id;
    }

    /**
     * Moves an ACTIVE suggestion to a new status. Returns false if it was no longer ACTIVE
     * (for example two clicks raced), so a decision can never overwrite another one.
     */
    public boolean closeActive(long id, RecommendationStatus status, Instant decidedAt, Instant acceptedStart) {
        int changed = jdbc.update("""
                UPDATE recommendation
                SET status = ?, decided_at = ?, accepted_start = ?
                WHERE id = ? AND status = 'ACTIVE'
                """,
                status.name(),
                Timestamp.from(decidedAt),
                acceptedStart == null ? null : Timestamp.from(acceptedStart),
                id);
        return changed == 1;
    }

    /** Expires the household's ACTIVE suggestions whose start has passed; returns their ids. */
    public List<Long> expirePassed(long householdId, Instant now) {
        return jdbc.queryForList("""
                UPDATE recommendation SET status = 'EXPIRED', decided_at = ?
                WHERE household_id = ? AND status = 'ACTIVE' AND suggested_start <= ?
                RETURNING id
                """, Long.class, Timestamp.from(now), householdId, Timestamp.from(now));
    }

    public void logEvent(long recommendationId, String eventType, Instant occurredAt,
                         Instant startAt, boolean overridden) {
        jdbc.update("""
                INSERT INTO recommendation_event
                    (recommendation_id, event_type, occurred_at, start_at, overridden)
                VALUES (?, ?, ?, ?, ?)
                """,
                recommendationId,
                eventType,
                Timestamp.from(occurredAt),
                startAt == null ? null : Timestamp.from(startAt),
                overridden);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}

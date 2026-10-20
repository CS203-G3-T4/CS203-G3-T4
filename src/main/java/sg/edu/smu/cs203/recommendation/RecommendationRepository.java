package sg.edu.smu.cs203.recommendation;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
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
                   r.created_at, r.decided_at, r.spike_start, r.snoozed_until,
                   r.confidence_cheaper_days, r.confidence_days_checked
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
            instant(rs, "decided_at"),
            instant(rs, "spike_start"),
            instant(rs, "snoozed_until"),
            rs.getObject("confidence_cheaper_days", Integer.class),
            rs.getObject("confidence_days_checked", Integer.class));

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

    /**
     * A household's suggestions in the given statuses created since the given time, most recent
     * decision (or creation) first, for the Recommendations page tabs (CSDT4-40).
     */
    public List<Recommendation> findByStatuses(long householdId, List<RecommendationStatus> statuses,
                                               Instant since, int limit) {
        String placeholders = String.join(", ", statuses.stream().map(s -> "?").toList());
        List<Object> args = new ArrayList<>();
        args.add(householdId);
        statuses.forEach(s -> args.add(s.name()));
        args.add(Timestamp.from(since));
        args.add(limit);
        return jdbc.query(SELECT + " WHERE r.household_id = ? AND r.status IN (" + placeholders + ")"
                        + " AND r.created_at >= ?"
                        + " ORDER BY COALESCE(r.decided_at, r.created_at) DESC, r.id DESC LIMIT ?",
                ROW_MAPPER, args.toArray());
    }

    /** Open suggestions for a household, ranked: biggest expected saving first (CSDT4-37). */
    public List<Recommendation> findActive(long householdId) {
        return jdbc.query(SELECT + " WHERE r.household_id = ? AND r.status = 'ACTIVE'"
                        + " ORDER BY r.est_saving DESC, r.suggested_start, r.id",
                ROW_MAPPER, householdId);
    }

    /** Outcome of the latest background recompute for a household (CSDT4-37). */
    public record RefreshStatus(Instant refreshedAt, String forecastSource, String forecastModel,
                                String message) {
    }

    public void saveRefresh(long householdId, RefreshStatus status) {
        jdbc.update("""
                INSERT INTO recommendation_refresh
                    (household_id, refreshed_at, forecast_source, forecast_model, message)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (household_id) DO UPDATE
                SET refreshed_at = EXCLUDED.refreshed_at, forecast_source = EXCLUDED.forecast_source,
                    forecast_model = EXCLUDED.forecast_model, message = EXCLUDED.message
                """,
                householdId,
                Timestamp.from(status.refreshedAt()),
                status.forecastSource(),
                status.forecastModel(),
                status.message());
    }

    public Optional<RefreshStatus> findRefresh(long householdId) {
        return jdbc.query("""
                SELECT refreshed_at, forecast_source, forecast_model, message
                FROM recommendation_refresh WHERE household_id = ?
                """, (rs, rowNum) -> new RefreshStatus(instant(rs, "refreshed_at"),
                        rs.getString("forecast_source"), rs.getString("forecast_model"),
                        rs.getString("message")), householdId)
                .stream().findFirst();
    }

    /** True if the resident already accepted, dismissed or acknowledged anything for this run. */
    public boolean decidedForRun(long applianceId, Instant usualStart) {
        return countForRun(applianceId, usualStart, "'ACCEPTED', 'DISMISSED', 'ACKNOWLEDGED'") > 0;
    }

    /**
     * True if the run was already moved (an accepted suggestion) or its spike warning acknowledged.
     * A dismissed shift suggestion doesn't count: the resident still runs at the usual time, so a
     * spike there is worth a warning (CSDT4-38).
     */
    public boolean movedOrAcknowledgedForRun(long applianceId, Instant usualStart) {
        return countForRun(applianceId, usualStart, "'ACCEPTED', 'ACKNOWLEDGED'") > 0;
    }

    private int countForRun(long applianceId, Instant usualStart, String statuses) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM recommendation"
                + " WHERE appliance_id = ? AND usual_start = ? AND status IN (" + statuses + ")",
                Integer.class, applianceId, Timestamp.from(usualStart));
        return count == null ? 0 : count;
    }

    public long insert(Recommendation recommendation) {
        Long id = jdbc.queryForObject("""
                INSERT INTO recommendation
                    (household_id, appliance_id, kind, suggested_start, usual_start, suggested_cost,
                     usual_cost, est_saving, reason, forecast_source, forecast_model, status, created_at,
                     spike_start, snoozed_until, confidence_cheaper_days, confidence_days_checked)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                Timestamp.from(recommendation.createdAt()),
                timestamp(recommendation.spikeStart()),
                timestamp(recommendation.snoozedUntil()),
                recommendation.confidenceCheaperDays(),
                recommendation.confidenceDaysChecked());
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

    /** Saves the run the resident agreed to when accepting (CSDT4-53): the appliance's power and length then. */
    public void saveRun(long id, BigDecimal powerKw, int runMinutes) {
        jdbc.update("UPDATE recommendation SET run_power_kw = ?, run_minutes = ? WHERE id = ?",
                powerKw, runMinutes, id);
    }

    private static final String ACCEPTED_RUNS = """
            SELECT r.id, r.appliance_id, a.name AS appliance_name, r.usual_start, r.accepted_start,
                   COALESCE(r.run_power_kw, a.power_kw) AS power_kw,
                   COALESCE(r.run_minutes, a.run_minutes) AS run_minutes, r.est_saving
            FROM recommendation r
            JOIN appliance a ON a.id = r.appliance_id
            WHERE r.household_id = ? AND r.status = 'ACCEPTED' AND r.kind = 'SHIFT_START'
            """;

    private static final RowMapper<AcceptedRun> ACCEPTED_RUN_MAPPER = (rs, rowNum) -> new AcceptedRun(
            rs.getLong("id"),
            rs.getLong("appliance_id"),
            rs.getString("appliance_name"),
            instant(rs, "usual_start"),
            instant(rs, "accepted_start"),
            rs.getBigDecimal("power_kw"),
            rs.getInt("run_minutes"),
            rs.getBigDecimal("est_saving"));

    /**
     * Accepted shift suggestions whose chosen start is in [from, to), earliest first (CSDT4-53).
     * Spike warnings are left out: "Got it" doesn't say when the appliance was run.
     */
    public List<AcceptedRun> findAcceptedRuns(long householdId, Instant from, Instant to) {
        return jdbc.query(ACCEPTED_RUNS + " AND r.accepted_start >= ? AND r.accepted_start < ?"
                        + " ORDER BY r.accepted_start, r.id",
                ACCEPTED_RUN_MAPPER, householdId, Timestamp.from(from), Timestamp.from(to));
    }

    /** The accepted shift suggestions among these ids (CSDT4-53). */
    public List<AcceptedRun> findAcceptedRuns(long householdId, List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", ids.stream().map(id -> "?").toList());
        List<Object> args = new ArrayList<>();
        args.add(householdId);
        args.addAll(ids);
        return jdbc.query(ACCEPTED_RUNS + " AND r.id IN (" + placeholders + ") ORDER BY r.accepted_start, r.id",
                ACCEPTED_RUN_MAPPER, args.toArray());
    }

    /**
     * Hides an open suggestion until the given time ("Remind me later", CSDT4-38/40). Returns false
     * if it was no longer open.
     */
    public boolean snooze(long id, Instant until) {
        return jdbc.update("""
                UPDATE recommendation SET snoozed_until = ?
                WHERE id = ? AND status = 'ACTIVE'
                """, Timestamp.from(until), id) == 1;
    }

    /**
     * Expires the household's ACTIVE suggestions whose start has passed; returns their ids. A spike
     * warning also expires once the usual start it warns about has passed.
     */
    public List<Long> expirePassed(long householdId, Instant now) {
        return jdbc.queryForList("""
                UPDATE recommendation SET status = 'EXPIRED', decided_at = ?
                WHERE household_id = ? AND status = 'ACTIVE'
                  AND (suggested_start <= ? OR (kind = 'AVOID' AND usual_start <= ?))
                RETURNING id
                """, Long.class, Timestamp.from(now), householdId, Timestamp.from(now), Timestamp.from(now));
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

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}

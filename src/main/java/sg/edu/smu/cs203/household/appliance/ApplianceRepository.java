package sg.edu.smu.cs203.household.appliance;

import java.sql.Timestamp;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ApplianceRepository {

    private static final String COLUMNS = """
            id, household_id, name, type, power_kw, run_minutes, flexibility,
            earliest_start, must_finish_by, usual_start, runs_per_week, enabled, notes,
            created_at, updated_at
            """;

    private static final RowMapper<Appliance> ROW_MAPPER = (rs, rowNum) -> new Appliance(
            rs.getLong("id"),
            rs.getLong("household_id"),
            rs.getString("name"),
            ApplianceType.valueOf(rs.getString("type")),
            rs.getBigDecimal("power_kw"),
            rs.getInt("run_minutes"),
            Flexibility.valueOf(rs.getString("flexibility")),
            rs.getObject("earliest_start", LocalTime.class),
            rs.getObject("must_finish_by", LocalTime.class),
            rs.getObject("usual_start", LocalTime.class),
            rs.getInt("runs_per_week"),
            rs.getBoolean("enabled"),
            rs.getString("notes"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    public ApplianceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Appliance> findByHousehold(long householdId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM appliance WHERE household_id = ? ORDER BY id",
                ROW_MAPPER, householdId);
    }

    /** Only finds the appliance if it belongs to this household. */
    public Optional<Appliance> findById(long householdId, long applianceId) {
        List<Appliance> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM appliance WHERE household_id = ? AND id = ?",
                ROW_MAPPER, householdId, applianceId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(rows.get(0));
    }

    public long insert(Appliance appliance) {
        Long id = jdbc.queryForObject("""
                INSERT INTO appliance
                    (household_id, name, type, power_kw, run_minutes, flexibility,
                     earliest_start, must_finish_by, usual_start, runs_per_week, enabled, notes,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                appliance.householdId(),
                appliance.name(),
                appliance.type().name(),
                appliance.powerKw(),
                appliance.runMinutes(),
                appliance.flexibility().name(),
                appliance.earliestStart(),
                appliance.mustFinishBy(),
                appliance.usualStart(),
                appliance.runsPerWeek(),
                appliance.enabled(),
                appliance.notes(),
                Timestamp.from(appliance.createdAt()),
                Timestamp.from(appliance.updatedAt()));
        return id;
    }

    /** Returns false if no appliance with this id belongs to the household. */
    public boolean update(Appliance appliance) {
        int changed = jdbc.update("""
                UPDATE appliance
                SET name = ?, type = ?, power_kw = ?, run_minutes = ?, flexibility = ?,
                    earliest_start = ?, must_finish_by = ?, usual_start = ?, runs_per_week = ?,
                    enabled = ?, notes = ?, updated_at = ?
                WHERE household_id = ? AND id = ?
                """,
                appliance.name(),
                appliance.type().name(),
                appliance.powerKw(),
                appliance.runMinutes(),
                appliance.flexibility().name(),
                appliance.earliestStart(),
                appliance.mustFinishBy(),
                appliance.usualStart(),
                appliance.runsPerWeek(),
                appliance.enabled(),
                appliance.notes(),
                Timestamp.from(appliance.updatedAt()),
                appliance.householdId(),
                appliance.id());
        return changed == 1;
    }

    /** Returns false if no appliance with this id belongs to the household. */
    public boolean delete(long householdId, long applianceId) {
        int changed = jdbc.update("DELETE FROM appliance WHERE household_id = ? AND id = ?",
                householdId, applianceId);
        return changed == 1;
    }
}

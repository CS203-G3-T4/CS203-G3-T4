package sg.edu.smu.cs203.household;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class HouseholdRepository {

    private static final String COLUMNS = """
            id, name, dwelling_type, occupants, plan_type, fixed_rate_cents_per_kwh,
            archetype_id, simulated, created_at, updated_at
            """;

    private static final RowMapper<Household> ROW_MAPPER = (rs, rowNum) -> new Household(
            rs.getLong("id"),
            rs.getString("name"),
            DwellingType.valueOf(rs.getString("dwelling_type")),
            rs.getInt("occupants"),
            PlanType.valueOf(rs.getString("plan_type")),
            rs.getBigDecimal("fixed_rate_cents_per_kwh"),
            rs.getObject("archetype_id", Long.class),
            rs.getBoolean("simulated"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    public HouseholdRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Household> findById(long id) {
        List<Household> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM household WHERE id = ?", ROW_MAPPER, id);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(rows.get(0));
    }

    public List<Household> findAll(boolean includeSimulated) {
        String where = "";
        if (!includeSimulated) {
            where = " WHERE simulated = FALSE";
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM household" + where + " ORDER BY id",
                ROW_MAPPER);
    }

    public boolean exists(long id) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM household WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    /** Inserts a household and returns the generated id. */
    public long insert(Household household) {
        Long id = jdbc.queryForObject("""
                INSERT INTO household
                    (name, dwelling_type, occupants, plan_type, fixed_rate_cents_per_kwh,
                     archetype_id, simulated, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                household.name(),
                household.dwellingType().name(),
                household.occupants(),
                household.planType().name(),
                household.fixedRateCentsPerKwh(),
                household.archetypeId(),
                household.simulated(),
                Timestamp.from(household.createdAt()),
                Timestamp.from(household.updatedAt()));
        return id;
    }

    /** Updates the editable profile fields. Returns false if the household does not exist. */
    public boolean update(Household household) {
        int changed = jdbc.update("""
                UPDATE household
                SET name = ?, dwelling_type = ?, occupants = ?, plan_type = ?,
                    fixed_rate_cents_per_kwh = ?, archetype_id = ?, updated_at = ?
                WHERE id = ?
                """,
                household.name(),
                household.dwellingType().name(),
                household.occupants(),
                household.planType().name(),
                household.fixedRateCentsPerKwh(),
                household.archetypeId(),
                Timestamp.from(household.updatedAt()),
                household.id());
        return changed == 1;
    }

    /**
     * Deletes a household. Its appliances go with it (ON DELETE CASCADE in V4).
     * Returns false if the household does not exist.
     */
    public boolean delete(long id) {
        return jdbc.update("DELETE FROM household WHERE id = ?", id) == 1;
    }
}

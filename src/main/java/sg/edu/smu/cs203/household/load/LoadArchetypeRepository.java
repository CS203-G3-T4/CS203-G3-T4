package sg.edu.smu.cs203.household.load;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import sg.edu.smu.cs203.household.DwellingType;

@Repository
public class LoadArchetypeRepository {

    private static final String COLUMNS =
            "id, code, dwelling_type, label, monthly_kwh, weekend_factor, source";

    private static final RowMapper<LoadArchetype> ROW_MAPPER = (rs, rowNum) -> new LoadArchetype(
            rs.getLong("id"),
            rs.getString("code"),
            DwellingType.valueOf(rs.getString("dwelling_type")),
            rs.getString("label"),
            rs.getBigDecimal("monthly_kwh"),
            rs.getBigDecimal("weekend_factor"),
            rs.getString("source"));

    private final JdbcTemplate jdbc;

    public LoadArchetypeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<LoadArchetype> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM load_archetype ORDER BY monthly_kwh",
                ROW_MAPPER);
    }

    public Optional<LoadArchetype> findById(long id) {
        List<LoadArchetype> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM load_archetype WHERE id = ?", ROW_MAPPER, id);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(rows.get(0));
    }

    public Optional<LoadArchetype> findByDwellingType(DwellingType dwellingType) {
        List<LoadArchetype> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM load_archetype WHERE dwelling_type = ?",
                ROW_MAPPER, dwellingType.name());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(rows.get(0));
    }
}

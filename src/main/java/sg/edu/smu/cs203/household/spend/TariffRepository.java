package sg.edu.smu.cs203.household.spend;

import java.sql.Date;
import java.sql.Time;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import sg.edu.smu.cs203.household.PlanType;

@Repository
public class TariffRepository {

    private final JdbcTemplate jdbc;

    public TariffRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every version of every charge for this plan that is in force on any day from from to to. */
    public List<TariffComponent> forPeriod(PlanType planType, LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT code, charge, unit, rate, applies_from, applies_to, effective_from, effective_to,
                       source, source_url
                FROM tariff_component
                WHERE plan_type = ? AND effective_from <= ? AND (effective_to IS NULL OR effective_to >= ?)
                ORDER BY id
                """, (rs, n) -> new TariffComponent(rs.getString("code"), rs.getString("charge"),
                        TariffComponent.Unit.valueOf(rs.getString("unit")), rs.getBigDecimal("rate"),
                        time(rs.getTime("applies_from")), time(rs.getTime("applies_to")),
                        rs.getDate("effective_from").toLocalDate(),
                        rs.getDate("effective_to") == null ? null : rs.getDate("effective_to").toLocalDate(),
                        rs.getString("source"), rs.getString("source_url")),
                planType.name(), Date.valueOf(to), Date.valueOf(from));
    }

    private static java.time.LocalTime time(Time value) {
        return value == null ? null : value.toLocalTime();
    }
}

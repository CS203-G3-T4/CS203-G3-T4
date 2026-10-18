package sg.edu.smu.cs203.household.budget;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BudgetRepository {
    public record Settings(BigDecimal monthlyBudgetSgd, BigDecimal planningRateCentsPerKwh) {
    }

    private final JdbcTemplate jdbc;

    public BudgetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Settings> find(long householdId) {
        List<Settings> rows = jdbc.query("""
                SELECT monthly_budget_sgd, planning_rate_cents_per_kwh
                FROM household_budget WHERE household_id = ?
                """, (rs, row) -> new Settings(rs.getBigDecimal("monthly_budget_sgd"),
                rs.getBigDecimal("planning_rate_cents_per_kwh")), householdId);
        return rows.stream().findFirst();
    }

    public void save(long householdId, BudgetRequest request, Instant now) {
        jdbc.update("""
                INSERT INTO household_budget
                    (household_id, monthly_budget_sgd, planning_rate_cents_per_kwh, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (household_id) DO UPDATE SET
                    monthly_budget_sgd = EXCLUDED.monthly_budget_sgd,
                    planning_rate_cents_per_kwh = EXCLUDED.planning_rate_cents_per_kwh,
                    updated_at = EXCLUDED.updated_at
                """, householdId, request.monthlyBudgetSgd(), request.planningRateCentsPerKwh(),
                Timestamp.from(now));
    }
}

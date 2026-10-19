package sg.edu.smu.cs203.security;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class UserAccountRepository {

    private static final RowMapper<UserAccount> ROW_MAPPER = (rs, rowNum) -> {
        Long householdId = rs.getObject("household_id", Long.class);
        return new UserAccount(
                rs.getString("username"),
                rs.getString("password_hash"),
                AccountRole.valueOf(rs.getString("role")),
                householdId,
                rs.getBoolean("simulated_household"));
    };

    private final JdbcTemplate jdbc;

    public UserAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserAccount> findByUsername(String username) {
        return jdbc.query("""
                SELECT ua.username, ua.password_hash, ua.role, ua.household_id,
                       COALESCE(h.simulated, FALSE) AS simulated_household
                FROM user_account ua
                LEFT JOIN household h ON h.id = ua.household_id
                WHERE ua.username = ?
                """, ROW_MAPPER, username).stream().findFirst();
    }
}

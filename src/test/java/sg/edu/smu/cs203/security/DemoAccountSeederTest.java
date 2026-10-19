package sg.edu.smu.cs203.security;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

class DemoAccountSeederTest {

    private static final String INSERT_ACCOUNT_SQL = """
            INSERT INTO user_account (username, password_hash, role, household_id)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (username) DO NOTHING
            """;

    @Test
    void createsHashedResidentAndAdminAccountsForTheDemoProfile() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        PasswordEncoder encoder = new SecurityConfiguration().passwordEncoder();
        when(jdbc.queryForObject(anyString(), eq(Long.class), anyString())).thenReturn(42L);
        when(jdbc.update(eq(INSERT_ACCOUNT_SQL), any(Object[].class))).thenReturn(1);

        new DemoAccountSeeder(jdbc, encoder, "resident", "resident-demo",
                "admin", "admin-demo").run(null);

        verify(jdbc).queryForObject(
                org.mockito.ArgumentMatchers.contains("WHERE name = ?"),
                eq(Long.class), eq("Home"));
        ArgumentCaptor<Object[]> accountArguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(2)).update(eq(INSERT_ACCOUNT_SQL), accountArguments.capture());
        List<Object[]> seeded = accountArguments.getAllValues();
        assertTrue(seeded.stream().anyMatch(args -> args[0].equals("resident")
                && args[2].equals("RESIDENT") && args[3].equals(42L)
                && encoder.matches("resident-demo", (String) args[1])));
        assertTrue(seeded.stream().anyMatch(args -> args[0].equals("admin")
                && args[2].equals("ADMIN") && args[3] == null
                && encoder.matches("admin-demo", (String) args[1])));
    }
}

package sg.edu.smu.cs203.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Profile("demo")
final class DemoAccountSeeder implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(DemoAccountSeeder.class);
    private static final String HOUSEHOLD_NAME = "Home";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final String residentUsername;
    private final String residentPassword;
    private final String adminUsername;
    private final String adminPassword;

    DemoAccountSeeder(
            JdbcTemplate jdbc,
            PasswordEncoder passwordEncoder,
            @Value("${wattly.demo.accounts.resident.username}") String residentUsername,
            @Value("${wattly.demo.accounts.resident.password}") String residentPassword,
            @Value("${wattly.demo.accounts.admin.username}") String adminUsername,
            @Value("${wattly.demo.accounts.admin.password}") String adminPassword) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.residentUsername = residentUsername;
        this.residentPassword = residentPassword;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long householdId = jdbc.queryForObject("""
                SELECT id FROM household
                WHERE name = ? AND COALESCE(simulated, FALSE) = FALSE
                ORDER BY id
                LIMIT 1
                """, Long.class, HOUSEHOLD_NAME);
        if (householdId == null) {
            throw new IllegalStateException(
                    "Cannot seed the demo resident account: the Home property was not found.");
        }

        seedAccount(residentUsername, residentPassword, AccountRole.RESIDENT, householdId);
        seedAccount(adminUsername, adminPassword, AccountRole.ADMIN, null);
    }

    private void seedAccount(String username, String password, AccountRole role, Long householdId) {
        int inserted = jdbc.update("""
                INSERT INTO user_account (username, password_hash, role, household_id)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (username) DO NOTHING
                """, username, passwordEncoder.encode(password), role.name(), householdId);
        logger.info("Demo {} account '{}' {}", role.name().toLowerCase(), username,
                inserted == 1 ? "created" : "already exists");
    }
}

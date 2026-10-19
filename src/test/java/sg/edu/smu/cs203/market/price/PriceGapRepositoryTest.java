package sg.edu.smu.cs203.market.price;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Missing half-hour SQL (CSDT4-77) against real PostgreSQL in a throwaway schema. */
@EnabledIfEnvironmentVariable(named = "WATTLY_TEST_DB_URL", matches = ".+")
class PriceGapRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-10-19T00:00:00Z");

    private final String schema = "price_gaps_" + UUID.randomUUID().toString().replace("-", "");
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private PriceGapRepository gaps;
    private MarketPriceRepository prices;

    @BeforeEach
    void migrate() {
        String url = System.getenv("WATTLY_TEST_DB_URL");
        String user = System.getenv().getOrDefault("WATTLY_TEST_DB_USER", "wattly_test");
        String password = System.getenv().getOrDefault("WATTLY_TEST_DB_PASSWORD", "");
        Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema).load().migrate();
        dataSource = new SingleConnectionDataSource(url, user, password, true);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("SET search_path TO " + schema);
        gaps = new PriceGapRepository(jdbc);
        prices = new MarketPriceRepository(jdbc);
    }

    @AfterEach
    void dropSchema() {
        jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        dataSource.destroy();
    }

    @Test
    void listsHalfHoursWithNoStoredPriceInTheWindow() {
        // Stored: 00:00, 00:30, 02:00. Window [00:00, 03:00) is missing 01:00, 01:30 and 02:30.
        store(0);
        store(30);
        store(120);
        store(180); // the window end itself is excluded either way

        assertThat(gaps.findMissingIntervals(T0, T0.plusSeconds(3 * 3600))).containsExactly(
                T0.plusSeconds(3600), T0.plusSeconds(5400), T0.plusSeconds(9000));
        assertThat(gaps.findMissingIntervals(T0, T0)).isEmpty();
    }

    @Test
    void savesAndReadsBackTheLatestCheck() {
        assertThat(gaps.findLatest()).isEmpty();
        gaps.save(new PriceGapCheck(T0, T0.minusSeconds(3600), T0, List.of()));
        PriceGapCheck latest = new PriceGapCheck(T0.plusSeconds(60), T0.minusSeconds(3600), T0,
                List.of(T0.minusSeconds(1800), T0.minusSeconds(3600)));
        gaps.save(latest);

        assertThat(gaps.findLatest()).get().satisfies(check -> {
            assertThat(check.checkedAt()).isEqualTo(latest.checkedAt());
            assertThat(check.windowStart()).isEqualTo(latest.windowStart());
            assertThat(check.missingIntervals()).containsExactly(T0.minusSeconds(3600), T0.minusSeconds(1800));
        });
    }

    private void store(long minutes) {
        Instant interval = T0.plusSeconds(minutes * 60);
        prices.upsert(new MarketPrice("NEMS_SN_SG", interval, interval.plusSeconds(60), interval.plusSeconds(120),
                new BigDecimal("190.0000"), new BigDecimal("7000.000"), null));
    }
}

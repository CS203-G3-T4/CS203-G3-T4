package sg.edu.smu.cs203.market.price;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** SQL behind feed health (CSDT4-25), against real PostgreSQL in a throwaway schema. */
@EnabledIfEnvironmentVariable(named = "WATTLY_TEST_DB_URL", matches = ".+")
class FeedHealthRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-10-19T00:00:00Z");

    private final String schema = "feed_health_" + UUID.randomUUID().toString().replace("-", "");
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private FeedIncidentRepository incidents;
    private IngestionRunRepository runs;

    @BeforeEach
    void migrate() {
        String url = System.getenv("WATTLY_TEST_DB_URL");
        String user = System.getenv().getOrDefault("WATTLY_TEST_DB_USER", "wattly_test");
        String password = System.getenv().getOrDefault("WATTLY_TEST_DB_PASSWORD", "");
        Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema).load().migrate();
        dataSource = new SingleConnectionDataSource(url, user, password, true);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("SET search_path TO " + schema);
        incidents = new FeedIncidentRepository(jdbc);
        runs = new IngestionRunRepository(jdbc);
    }

    @AfterEach
    void dropSchema() {
        jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        dataSource.destroy();
    }

    @Test
    void onlyOneIncidentCanBeOpenAndClosingEndsIt() {
        assertThat(incidents.open(T0, "first")).isTrue();
        assertThat(incidents.open(T0.plusSeconds(300), "second")).isFalse();
        incidents.countFailedPoll();

        assertThat(incidents.findOpen()).get().satisfies(open -> {
            assertThat(open.reason()).isEqualTo("first");
            assertThat(open.failedPolls()).isEqualTo(2);
        });
        assertThat(incidents.closeOpen(T0.plusSeconds(600))).isTrue();
        assertThat(incidents.findOpen()).isEmpty();
        assertThat(incidents.closeOpen(T0.plusSeconds(900))).isFalse();

        assertThat(incidents.open(T0.plusSeconds(1200), "third")).isTrue();
        assertThat(incidents.findRecent(10)).extracting(FeedIncident::reason).containsExactly("third", "first");
        assertThat(incidents.findOverlapping(T0.plusSeconds(700), T0.plusSeconds(1300)))
                .extracting(FeedIncident::reason).containsExactly("third");
        assertThat(incidents.findOverlapping(T0.minusSeconds(60), T0.plusSeconds(60)))
                .extracting(FeedIncident::endedAt).containsExactly(T0.plusSeconds(600));
    }

    @Test
    void runQueriesCountFailuresSinceLastSuccessAndMonthlyFallbacks() {
        assertThat(runs.findFirstStartedAt()).isEmpty();
        assertThat(runs.findLastSuccessAt()).isEmpty();
        assertThat(runs.countConsecutiveFailures()).isZero();

        save(0, IngestionStatus.FAILURE);
        save(300, IngestionStatus.SUCCESS);
        save(600, IngestionStatus.STALE_SOURCE);
        save(900, IngestionStatus.FAILURE);

        assertThat(runs.findFirstStartedAt()).contains(T0);
        assertThat(runs.findLastSuccessAt()).contains(T0.plusSeconds(301));
        assertThat(runs.countConsecutiveFailures()).isEqualTo(2);
        assertThat(runs.countFallbacks(T0, T0.plusSeconds(1000))).isEqualTo(3);
        assertThat(runs.countFallbacks(T0.plusSeconds(1), T0.plusSeconds(900))).isEqualTo(1);
    }

    private void save(long offsetSeconds, IngestionStatus status) {
        Instant start = T0.plusSeconds(offsetSeconds);
        runs.save(new IngestionRun(start, start.plusSeconds(1), status, null,
                status == IngestionStatus.SUCCESS ? null : "x"));
    }
}

package sg.edu.smu.cs203.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** CSDT4-53: the run saved on accept, and the fallback for older rows, against real PostgreSQL. */
@EnabledIfEnvironmentVariable(named = "WATTLY_TEST_DB_URL", matches = ".+")
class RealisedSavingsRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-10-14T05:00:00Z"); // 13:00 Singapore

    private final String schema = "realised_" + UUID.randomUUID().toString().replace("-", "");
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private RecommendationRepository repository;
    private long householdId;
    private long applianceId;
    private BigDecimal currentPower;
    private int currentMinutes;

    @BeforeEach
    void migrate() {
        String url = System.getenv("WATTLY_TEST_DB_URL");
        String user = System.getenv().getOrDefault("WATTLY_TEST_DB_USER", "wattly_test");
        String password = System.getenv().getOrDefault("WATTLY_TEST_DB_PASSWORD", "");
        Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema).load().migrate();
        dataSource = new SingleConnectionDataSource(url, user, password, true);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("SET search_path TO " + schema);
        repository = new RecommendationRepository(jdbc);
        Map<String, Object> appliance = jdbc.queryForMap(
                "SELECT id, household_id, power_kw, run_minutes FROM appliance ORDER BY id LIMIT 1");
        applianceId = ((Number) appliance.get("id")).longValue();
        householdId = ((Number) appliance.get("household_id")).longValue();
        currentPower = (BigDecimal) appliance.get("power_kw");
        currentMinutes = ((Number) appliance.get("run_minutes")).intValue();
    }

    @AfterEach
    void dropSchema() {
        jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        dataSource.destroy();
    }

    @Test
    void theRunSavedOnAcceptIsUsedEvenAfterTheApplianceIsEdited() {
        long id = accepted(T0);
        repository.saveRun(id, new BigDecimal("2.000"), 45);
        jdbc.update("UPDATE appliance SET power_kw = 9, run_minutes = 120 WHERE id = ?", applianceId);

        List<AcceptedRun> runs = repository.findAcceptedRuns(householdId, T0.minusSeconds(60), T0.plusSeconds(60));

        assertThat(runs).singleElement().satisfies(run -> {
            assertThat(run.recommendationId()).isEqualTo(id);
            assertThat(run.chosenStart()).isEqualTo(T0);
            assertThat(run.usualStart()).isEqualTo(T0.plusSeconds(6 * 3600));
            assertThat(run.powerKw()).isEqualByComparingTo("2.0");
            assertThat(run.runMinutes()).isEqualTo(45);
        });
    }

    @Test
    void olderAcceptedSuggestionsFallBackToTheAppliancesCurrentSettings() {
        long id = accepted(T0);

        assertThat(repository.findAcceptedRuns(householdId, List.of(id))).singleElement().satisfies(run -> {
            assertThat(run.powerKw()).isEqualByComparingTo(currentPower);
            assertThat(run.runMinutes()).isEqualTo(currentMinutes);
        });
    }

    @Test
    void onlyAcceptedShiftSuggestionsChosenInsideThePeriodAreFound() {
        long inside = accepted(T0);
        accepted(T0.plusSeconds(7 * 86400)); // next week
        long dismissed = repository.insert(recommendation(Recommendation.SHIFT_START, T0.plusSeconds(1800)));
        repository.closeActive(dismissed, RecommendationStatus.DISMISSED, T0, null);

        assertThat(repository.findAcceptedRuns(householdId, T0.minusSeconds(86400), T0.plusSeconds(86400)))
                .extracting(AcceptedRun::recommendationId).containsExactly(inside);
        assertThat(repository.findAcceptedRuns(householdId, List.of())).isEmpty();
    }

    private long accepted(Instant chosen) {
        long id = repository.insert(recommendation(Recommendation.SHIFT_START, chosen));
        assertThat(repository.closeActive(id, RecommendationStatus.ACCEPTED, chosen.minusSeconds(3600), chosen)).isTrue();
        return id;
    }

    private Recommendation recommendation(String kind, Instant suggested) {
        return new Recommendation(null, householdId, applianceId, null, kind, suggested,
                suggested.plusSeconds(6 * 3600), new BigDecimal("0.10"), new BigDecimal("0.40"),
                new BigDecimal("0.30"), "reason", PriceForecast.DEMO, "demo", RecommendationStatus.ACTIVE, null,
                suggested.minusSeconds(7200), null);
    }
}

package sg.edu.smu.cs203.forecast;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named="WATTLY_TEST_DB_URL",matches=".+")
class ForecastMigrationUpgradeTest {
    @ParameterizedTest
    @ValueSource(ints={12,13,14})
    void legacyForecastVersionRequiresAnExplicitUpgradeDecisionAndIsNeverRepaired(int legacyVersion) throws Exception {
        String url=System.getenv("WATTLY_TEST_DB_URL");
        String user=System.getenv().getOrDefault("WATTLY_TEST_DB_USER","wattly_test");
        String password=System.getenv().getOrDefault("WATTLY_TEST_DB_PASSWORD","");
        String schema="legacy_forecast_"+UUID.randomUUID().toString().replace("-","");
        var directory=java.nio.file.Files.createTempDirectory("legacy-forecast-migrations-");
        try {
            var source=java.nio.file.Path.of("src/main/resources/db/migration");
            try (var files=java.nio.file.Files.list(source)) {
                for (var file:files.toList()) {
                    int version=Integer.parseInt(file.getFileName().toString().split("__")[0].substring(1));
                    if (version<legacyVersion) java.nio.file.Files.copy(file,directory.resolve(file.getFileName()));
                }
            }
            java.nio.file.Files.copy(source.resolve("V15__forecast_operations.sql"),directory.resolve("V"+legacyVersion+"__forecast_operations.sql"));
            Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema)
                    .locations("filesystem:"+directory).load().migrate();
            try (var connection=DriverManager.getConnection(url,user,password);var sql=connection.createStatement()) {
                sql.execute("SET search_path TO "+schema);
                sql.execute("INSERT INTO model_run(version,manifest,registered_at,usable_from,promotion_state) "
                        +"VALUES ('LEGACY_PRESERVED','{}','2026-10-18T00:00:00Z','2026-10-18T00:00:00Z','APPROVED')");
                assertThatThrownBy(()->Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema)
                        .load().migrate()).isInstanceOf(org.flywaydb.core.api.FlywayException.class);
                try (var rows=sql.executeQuery("SELECT script FROM flyway_schema_history WHERE version='"+legacyVersion+"'")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("V"+legacyVersion+"__forecast_operations.sql");
                }
                try (var rows=sql.executeQuery("SELECT count(*) FROM model_run WHERE version='LEGACY_PRESERVED'")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(1);
                }
            }
        } finally {
            try (var files=java.nio.file.Files.walk(directory)) {
                for (var file:files.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(file);
            }
        }
    }
    @Test void currentMainSchemaUpgradesWithoutLosingBudgetRecommendationOrForecastHistory() throws Exception {
        String url=System.getenv("WATTLY_TEST_DB_URL");
        String user=System.getenv().getOrDefault("WATTLY_TEST_DB_USER","wattly_test");
        String password=System.getenv().getOrDefault("WATTLY_TEST_DB_PASSWORD","");
        String schema="main_upgrade_"+UUID.randomUUID().toString().replace("-","");
        Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).target("14").load().migrate();
        try (var connection=DriverManager.getConnection(url,user,password);var sql=connection.createStatement()) {
            sql.execute("SET search_path TO "+schema);
            sql.execute("INSERT INTO household_budget VALUES (1,125.50,29.5,'2026-10-18T00:00:00Z')");
            sql.execute("INSERT INTO recommendation_refresh VALUES (1,'2026-10-18T00:00:00Z','DEMO_FORECAST','preserved','keep')");
            sql.execute("INSERT INTO model_run(version,manifest,registered_at,usable_from,promotion_state) "
                    +"VALUES ('PRESERVED_MODEL','{}','2026-10-18T00:00:00Z','2026-10-18T00:00:00Z','APPROVED')");
            assertThat(sql.executeUpdate("""
                    INSERT INTO recommendation(household_id,appliance_id,kind,suggested_start,usual_start,
                        suggested_cost,usual_cost,est_saving,reason,forecast_source,forecast_model,status,
                        created_at,confidence_cheaper_days,confidence_days_checked)
                    SELECT household_id,id,'SHIFT_START','2026-10-18T01:00:00Z','2026-10-18T02:00:00Z',
                        1,2,1,repeat('x',550),'DEMO_FORECAST','preserved-confidence','ACTIVE',
                        '2026-10-18T00:00:00Z',15,20
                    FROM appliance WHERE household_id=1 ORDER BY id LIMIT 1
                    """)).isEqualTo(1);
            Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).load().migrate();
            try (var rows=sql.executeQuery("SELECT confidence_cheaper_days,confidence_days_checked,reason "
                    +"FROM recommendation WHERE forecast_model='preserved-confidence'")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(15);
                assertThat(rows.getInt(2)).isEqualTo(20); assertThat(rows.getString(3)).hasSize(550);
            }
            try (var rows=sql.executeQuery("SELECT monthly_budget_sgd FROM household_budget WHERE household_id=1")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getBigDecimal(1)).isEqualByComparingTo(new BigDecimal("125.50"));
            }
            try (var rows=sql.executeQuery("SELECT version,attempt_id FROM model_run WHERE version='PRESERVED_MODEL'")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("PRESERVED_MODEL");
                assertThat(rows.getString(2)).isNull();
            }
            try (var rows=sql.executeQuery("SELECT forecast_model,message FROM recommendation_refresh WHERE household_id=1")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("preserved");
                assertThat(rows.getString(2)).isEqualTo("keep");
            }
            try (var rows=sql.executeQuery("SELECT count(*) FROM baseline_validation")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isZero();
            }
            try (var rows=sql.executeQuery("SELECT max(version::int) FROM flyway_schema_history WHERE version IS NOT NULL")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(26);
            }
        }
    }
}

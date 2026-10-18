package sg.edu.smu.cs203.forecast;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named="WATTLY_TEST_DB_URL",matches=".+")
class ForecastMigrationUpgradeTest {
    @Test void mainBudgetSchemaUpgradesWithoutLosingBudgetOrForecastHistory() throws Exception {
        String url=System.getenv("WATTLY_TEST_DB_URL");
        String user=System.getenv().getOrDefault("WATTLY_TEST_DB_USER","wattly_test");
        String password=System.getenv().getOrDefault("WATTLY_TEST_DB_PASSWORD","");
        String schema="main_upgrade_"+UUID.randomUUID().toString().replace("-","");
        Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).target("11").load().migrate();
        try (var connection=DriverManager.getConnection(url,user,password);var sql=connection.createStatement()) {
            sql.execute("SET search_path TO "+schema);
            sql.execute("INSERT INTO household_budget VALUES (1,125.50,29.5,'2026-10-18T00:00:00Z')");
            sql.execute("INSERT INTO model_run(version,manifest,registered_at,usable_from,promotion_state) "
                    +"VALUES ('PRESERVED_MODEL','{}','2026-10-18T00:00:00Z','2026-10-18T00:00:00Z','APPROVED')");
            Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).load().migrate();
            try (var rows=sql.executeQuery("SELECT monthly_budget_sgd FROM household_budget WHERE household_id=1")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getBigDecimal(1)).isEqualByComparingTo(new BigDecimal("125.50"));
            }
            try (var rows=sql.executeQuery("SELECT version,attempt_id FROM model_run WHERE version='PRESERVED_MODEL'")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("PRESERVED_MODEL");
                assertThat(rows.getString(2)).isNull();
            }
            try (var rows=sql.executeQuery("SELECT count(*) FROM baseline_validation")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isZero();
            }
            try (var rows=sql.executeQuery("SELECT max(version::int) FROM flyway_schema_history WHERE version IS NOT NULL")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(12);
            }
        }
    }
}

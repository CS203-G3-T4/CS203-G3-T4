package sg.edu.smu.cs203.forecast;

import java.nio.file.*;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="WATTLY_TEST_DB_URL",matches=".+")
class TrialMigrationCoexistenceTest {
    @Test void completeReplayV23ThroughV25ThenTrialV26PreservesDataAndValidatesWithoutReexecution() throws Exception {
        String url=System.getenv("WATTLY_TEST_DB_URL"),user=System.getenv().getOrDefault("WATTLY_TEST_DB_USER","wattly_test"),password=System.getenv().getOrDefault("WATTLY_TEST_DB_PASSWORD","");
        String schema="trial_coexist_"+UUID.randomUUID().toString().replace("-","");
        Path directory=Files.createTempDirectory("trial-coexist-migrations-");
        try {
            try(var files=Files.list(Path.of("src/main/resources/db/migration"))) {
                for(Path file:files.toList())Files.copy(file,directory.resolve(file.getFileName()));
            }
            var base=Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema)
                    .locations("filesystem:"+directory).target("22").load();base.migrate();
            try(var connection=DriverManager.getConnection(url,user,password);var sql=connection.createStatement()) {
                sql.execute("SET search_path TO "+schema);
                sql.execute("INSERT INTO household_budget VALUES (1,125.50,29.5,'2026-10-18T00:00:00Z')");
                // Simulate the entire replay PR already deployed, rather than only its first migration.
                var replay=Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema)
                        .locations("filesystem:"+directory).target("25").load();replay.migrate();replay.validate();
                sql.execute("INSERT INTO forecast_run(id,as_of,generated_at,origin_slot,input_revision,input_snapshot,mode,model_type,model_version,selected_model,quality_flags,stale,status,is_demo) VALUES "
                    +"('REPLAY_PRESERVED','2026-10-18T00:00Z','2026-10-18T00:00Z','2026-10-18T00:00Z','fixture','[]','REPLAY','BASELINE','B3-v1','B3','[]',false,'AVAILABLE',true)");
                var upgrade=Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema)
                        .locations("filesystem:"+directory).load();
                assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(2);upgrade.validate();
                try(var rows=sql.executeQuery("SELECT script FROM flyway_schema_history WHERE version='23'")) {
                    assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("V23__replay_prices_and_household.sql");
                }
                try(var rows=sql.executeQuery("SELECT script FROM flyway_schema_history WHERE version='24'")) {
                    assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("V24__mark_demo_forecast_runs.sql");
                }
                try(var rows=sql.executeQuery("SELECT script FROM flyway_schema_history WHERE version='25'")) {
                    assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("V25__drop_redundant_replay_price_index.sql");
                }
                try(var rows=sql.executeQuery("SELECT script FROM flyway_schema_history WHERE version='26'")) {
                    assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("V26__live_comparison_and_savings_ranges.sql");
                }
                try(var rows=sql.executeQuery("SELECT count(*) FROM live_forecast_trial")) {assertThat(rows.next()).isTrue();assertThat(rows.getInt(1)).isZero();}
                try(var rows=sql.executeQuery("SELECT count(*) FROM replay_price")) {assertThat(rows.next()).isTrue();}
                try(var rows=sql.executeQuery("SELECT monthly_budget_sgd FROM household_budget WHERE household_id=1")) {assertThat(rows.next()).isTrue();assertThat(rows.getBigDecimal(1)).isEqualByComparingTo("125.50");}
                try(var rows=sql.executeQuery("SELECT is_demo FROM forecast_run WHERE id='REPLAY_PRESERVED'")) {assertThat(rows.next()).isTrue();assertThat(rows.getBoolean(1)).isTrue();}
                try(var rows=sql.executeQuery("SELECT count(*) FROM pg_indexes WHERE schemaname=current_schema() AND indexname='idx_replay_price_interval'")) {assertThat(rows.next()).isTrue();assertThat(rows.getInt(1)).isZero();}
                assertThat(upgrade.migrate().migrationsExecuted).isZero();
                assertThat(upgrade.migrate().migrationsExecuted).isZero();upgrade.validate();
            }
        } finally {
            try(var connection=DriverManager.getConnection(url,user,password);var sql=connection.createStatement()){sql.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");}
            try(var files=Files.walk(directory)){for(Path file:files.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(file);}
        }
    }
}

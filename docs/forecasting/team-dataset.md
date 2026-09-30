# Same dataset on every presentation laptop

Give every teammate the same `wattly-f3-team-2026-09-30.tar.gz` archive, separately
from the repository. It contains a consistent PostgreSQL presentation-schema
snapshot, the fixed experimental chart JSON, evaluation reports, checksums and
database verification SQL. No Python model is required for the offline chart or
the Java baseline. Production AI still requires a separately approved model.

The snapshot was exported with PostgreSQL 16 from the separate demo instance.
The repository's PostgreSQL 17 Compose service can restore it. Use the F3 branch
`feature/CSDT4-AIForecaster&BaselineEvaluator`; its migrations match this snapshot.
Teammates need Docker Compose and Java 21. Commands below use Bash (Linux/WSL).

## Restore a new database

Extract the archive outside the repository. From the repository root, set the
absolute path to its extracted directory:

```bash
export WATTLY_TEAM_BUNDLE=/absolute/path/to/wattly-f3-team-2026-09-30
(cd "$WATTLY_TEAM_BUNDLE" && sha256sum --check SHA256SUMS)
docker compose up -d postgres
# Wait until PostgreSQL is ready before continuing.
docker compose exec -T postgres pg_isready -U energy_market -d energy_market
docker compose exec -T postgres psql -U energy_market -d postgres \
  -v ON_ERROR_STOP=1 -c 'CREATE DATABASE wattly_f3_team;'
docker compose exec -T postgres pg_restore -U energy_market -d wattly_f3_team \
  --no-owner --no-privileges --single-transaction --exit-on-error \
  < "$WATTLY_TEAM_BUNDLE/wattly-f3.dump"
docker compose exec -T postgres psql -U energy_market -d wattly_f3_team \
  -X -q -A -t -v ON_ERROR_STOP=1 < "$WATTLY_TEAM_BUNDLE/verify.sql" \
  > "$WATTLY_TEAM_BUNDLE/restored-data.tsv"
diff -u "$WATTLY_TEAM_BUNDLE/expected-data.tsv" "$WATTLY_TEAM_BUNDLE/restored-data.tsv"
```

An empty diff proves all 17 table counts and row fingerprints match the snapshot.
Verify before starting live ingestion. If `CREATE DATABASE` says it already exists,
stop and choose another new database name throughout these commands. Do not restore
over a teammate's existing database. The archive restores the `f3_presentation`
schema and its Flyway history; it does not require the original `wattly_demo` role.

## Start the same presentation

Keep the default credentials above only for the repository's local development
Compose service. If the team's Compose credentials were changed, use those values.

```bash
export DB_URL="jdbc:postgresql://localhost:${POSTGRES_PORT:-55432}/wattly_f3_team?currentSchema=f3_presentation"
export DB_USER=energy_market DB_PASSWORD=energy_market
export FORECAST_MODE=LIVE
export FORECAST_IMPORT_FILE=
export FORECAST_PYTHON_URL=http://127.0.0.1:8001
export USEP_POLLING_ENABLED=true NEA_DAILY_POLLING_ENABLED=true
source scripts/forecast-period-mapping.env
./mvnw -DskipTests package
java -jar target/energy-market-service-0.0.1-SNAPSHOT.jar \
  --server.address=127.0.0.1 --server.port=8080 \
  --spring.flyway.default-schema=f3_presentation \
  --spring.web.resources.static-locations="classpath:/static/,file:$WATTLY_TEAM_BUNDLE/public/"
```

Open `http://localhost:8080/forecast-demo.html`. Everyone sees the same saved
actuals, AI/B1/B2 predictions and evaluation metrics. These are explicitly dated
experimental results, not live AI predictions. The archive preserves the original
presentation experiment; the newer accuracy study is included as separate reports.

After pulling the updated F3 code and rebuilding the jar, the page defaults to the
latest HGB_MAE candidate: **224.41** diagnostic MAE versus B1's **234.31**. Its
matching predictions and accuracy-study table are packaged in the application as
`forecast-demo-latest.json`. Validation MAE is **369.88**; the original model's is
**405.24**, and the training median wins overall at **332.44**. September 28 is
already-seen diagnostic evidence, not an unseen accuracy confirmation. Use the
experiment selector to show the preserved original model at **273.55** MAE.
The existing archive and database restore can be reused; only the application
needs rebuilding/restarting. The new results do not approve live AI serving.

The live panel uses the restored history, the application's normal startup poll
and subsequent half-hour polls. An absent/unapproved Python model selects an
eligible Java baseline. Freshness and approval guards remain active. A snapshot
cannot guarantee live availability during an upstream outage or fill a later gap.
Check `/api/v1/forecast/latest` for the exact reason if the live panel is unavailable.

## Keep the dataset identical or extend it live

All copies start identical. With polling enabled, prices, receipt timestamps and
saved forecasts can diverge as each laptop collects independently. The offline
chart and metrics remain identical because their JSON is fixed.

For a presentation that must preserve every database row exactly, set
`USEP_POLLING_ENABLED=false` and `NEA_DAILY_POLLING_ENABLED=false` before starting.
Use the dated offline comparison; old live forecasts correctly become stale and
non-actionable. Disabling polling does not replace the shared Clock or bypass
stale-data checks. Household edits would also make a local copy diverge.

The archive contains seeded demo households, price/weather history, ingestion
records and saved forecasts. It excludes collector files, runtime credentials,
database roles and production model approval artifacts. Restore verification does
not change the source database or interrupt the collector.

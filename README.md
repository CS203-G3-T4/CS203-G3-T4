# CS203 Household Energy Market Service

This Spring Boot service stores half-hourly Uniform Singapore Energy Price (USEP)
observations and Singapore two-hour weather forecasts in PostgreSQL. It polls the
[NEMS API](https://nems.sn.sg/) and NEA when the application starts. NEMS is checked
every five minutes and the two-hour NEA feed at 02 and 32 minutes past each hour in
Singapore time. NEMS is an unofficial public service.
Each feed retains its saved history when an upstream fails. No CSV files are written.

USEP is expressed in **SGD/MWh**. It is a wholesale market signal, not a complete household electricity tariff or bill rate.

## Run locally

Requirements: Java 21 or newer, and Docker with Compose (or a PostgreSQL server). The Maven wrapper downloads Maven automatically.

```sh
docker compose up -d postgres
./mvnw spring-boot:run
```

The default PostgreSQL connection is `jdbc:postgresql://localhost:55432/energy_market` with local development credentials `energy_market` / `energy_market`. Override it with `DB_URL`, `DB_USER`, and `DB_PASSWORD` for another database. The container port can be changed with `POSTGRES_PORT`.

The application applies the Flyway migration automatically. The source URL can be overridden with `USEP_FEED_URL`. Set `USEP_POLLING_ENABLED=false` to disable the startup and scheduled polls, such as when running against a prepared test database.

For local frontend testing, start with the opt-in demo profile:

```sh
docker compose up -d postgres
SPRING_PROFILES_ACTIVE=demo ./mvnw spring-boot:run
```

Open `http://localhost:8080/` to sign in. The root URL serves the static
`index.html` login page; its form submits credentials to the `/login` processing
endpoint.

It creates a resident account (`resident` / `resident-demo`) linked to Home and
an admin account (`admin` / `admin-demo`). Passwords are stored as
BCrypt hashes. The demo accounts are created only when missing, and the demo
profile should not be enabled for production deployments.

Sign in as the resident to view only Home. Sign in as the admin to manage the
seeded property portfolio: Home, Office 1, Office 2, Office 3, and Rented Condo.
The labels are defined directly in the V5 and V7 seed migrations. Flyway only
runs those seeds on a fresh database; an existing database that already applied
V5/V7 retains its old seed names, and editing applied migrations changes their
checksums. Use a fresh disposable local database to try the renamed seed data.

## Deploy

Production runs on Railway with the `prod` profile, which requires `DB_URL`,
`DB_USER` and `DB_PASSWORD`. See [docs/deployment.md](docs/deployment.md).

## Run an already restored team dataset

Copy `.env.team-demo.example` to `.env.team-demo`, set `DB_URL` to the restored
database and `WATTLY_TEAM_BUNDLE` to the extracted bundle directory, then run
`./scripts/run-team-demo.sh`. The script loads the local settings, builds the jar,
and serves the saved chart at `/forecast-demo.html`. The database must already be
running and restored; this command does not restore the dump.

The imported dump, chart, and reports are local dataset artifacts and are ignored
by Git. Share the original bundle separately. PostgreSQL holds the restored history;
the `.dump` file is its backup and `public/forecast-demo.json` supplies the dated chart.
Keep `.env.team-demo` local because it contains database credentials.
Once the database is restored, the dump and restore-verification files can be kept
outside this repository as backups. They are not needed to run the app. Keep
`src/main/resources/db/migration/`: Flyway uses those versioned files to validate
the existing schema and initialize new databases.

## API

- `GET /api/v1/market-prices/latest` returns the latest stored price and `LIVE` or `STALE` freshness.
- `GET /api/v1/market-prices?from=2026-09-23T00:00:00Z&to=2026-09-24T00:00:00Z` returns historical intervals. The range is at most 31 days.
- `GET /api/v1/market-prices/feed-status` reports the most recent poll result (`SUCCESS`, `STALE_SOURCE`, `FAILURE`, or `NEVER_POLLED`).
- `GET /actuator/health` reports application and database health.
- `/swagger-ui.html` displays the API documentation; `/v3/api-docs` returns the OpenAPI document.

`/latest` returns HTTP 503 with `freshness: UNAVAILABLE` if no price has ever been stored. Once a price has been stored, a feed outage does not erase it; the endpoint returns that value with `freshness: STALE`. A successful poll can still be classified as `STALE_SOURCE` if the upstream API responds with an old timestamp. The freshness limit defaults to 40 minutes.

## Tests

```sh
./mvnw test
```

The service targets Java 21. Tests currently cover timestamp normalization and the live, stale, and unavailable decisions. The database upsert uses PostgreSQL `ON CONFLICT` so repeated polls of an interval update one row rather than creating duplicates.

## Combined price and weather history

`V2__combined_observations.sql` adds `market_observation` (one distinct NEMS
payload, with first collection and last seen timestamps) and `weather_observation`
(one NEA issue/update pair, with its full forecast item). Existing `market_price`,
API endpoints, and NEMS ingestion status continue to operate.

The scheduler calls both feeds at startup. It then checks NEMS every five minutes,
so a price published just after a half-hour boundary is picked up on the next retry.
The two-hour NEA feed is still polled at :02/:32 Singapore time.
`USEP_POLL_CRON` and `NEA_POLL_CRON` override their respective schedules; `USEP_POLLING_ENABLED=false`
disables NEMS and both weather collectors by default (the daily collector can be enabled separately). `NEA_FEED_URL` overrides the default official endpoint:
https://api-open.data.gov.sg/v2/real-time/api/two-hr-forecast
Both clients use the existing bounded connection/read timeouts. Each feed is saved
independently. Upstream failures never delete prior rows; NEA failures are logged.
Each NEA response is saved transactionally, so invalid items roll back that batch.

Query `SELECT * FROM combined_observations ORDER BY source_updated_unix` for the
same 66-column layout and column order as `combined_2026-09-23 (1).csv`:

- `record_id`: SHA-256 of PostgreSQL's canonical price JSON; stable within this
  application, not guaranteed to reproduce the sample generator's hash.
- `source_updated_sgt`, `source_updated_unix`, `first_collected_sgt`,
  `last_seen_sgt`, `source_age_seconds_at_collection`, `feed_stale_at_collection`:
  source time, collection history and freshness at first collection (40-minute
  threshold by default). Duplicate prices only advance last seen.
- `usep_sgd_per_mwh`, `demand_forecast_mw`, `vcp_sgd_per_mwh`: NEMS `usep`,
  `demand`, `vcp`; missing VCP remains SQL NULL.
- `weather_match_status`, `weather_issued_sgt`, `weather_updated_sgt`,
  `weather_valid_start_sgt`, `weather_valid_end_sgt`: latest stored forecast issued
  AND updated no later than the price source time, with start <= source < end.
  Status is `matched_as_of_source_update` or `no_matching_weather`. Unmatched
  weather columns remain NULL. The view can fill a match when weather arrives later.
- `price_response_id`, `weather_response_id`: local observation IDs, not upstream
  IDs or the sample's capture-log IDs; repeated observations retain their IDs.
- 47 `weather_<Area_Name>` columns, in the sample's order (Ang Mo Kio through
  Yishun), holding each area's forecast. Additional future areas remain in JSON.
- `price_payload_json`, `weather_item_json`: the four mapped NEMS fields and the
  complete NEA item respectively, stored as JSONB.

Storage uses timezone-aware timestamps; the view formats `_sgt` columns as ISO
8601 strings with `+08:00`. This preserves a compatible shape for future CSV demo
import/export. No demo importer or automatic loading of the supplied CSV is enabled.
Existing historical market rows are not backfilled: their original first-collection
metadata is unavailable. Combined history starts with the next successful poll.

`src/test/resources/combined_observations_test.sql` validates the view on an isolated
PostgreSQL database after applying migrations, using `psql -v ON_ERROR_STOP=1 -f`.

## F2: households and appliances

Households, their electricity plan, appliances with time windows, and a modelled half-hourly
load calibrated to EMA averages. Pages: `/appliances.html`, `/settings.html`,
`/households.html` (add and delete households) and `/about-data.html`. Endpoints and the modelling method are in
[docs/f2-households.md](docs/f2-households.md). Migrations V4–V8 create the tables and load
demo households (the Tans, Lim, Ong, Nair, Rahman) plus 100 simulated households.

## Jira backlog alignment

See [the implementation-to-PBI map](docs/jira-alignment.md) for the verified Jira
keys, delivered backend contracts, and acceptance criteria still requiring UI,
team agreement, or later work. New dashboard-facing endpoints are
`GET /api/v1/prices/current` (one-decimal cents/kWh and staleness) and
`GET /api/v1/weather/today` (daily temperature forecast and configurable heat hint).
The `/dashboard.html` page displays these readings, marks stale and unavailable
states, and refreshes the market price every minute.
The two-hour NEA collector and 66-column demo view remain available.

The shared Clock lives in `common/TimeConfig` and uses Asia/Singapore. All business
time must use that injected clock; the PR template includes this review rule.
The existing price interval rule floors the source timestamp to a half-hour;
EMC settlement-period attribution still needs the CSDT4-11 spike.

## Shared Clock rule (CSDT4-21)

Constructor-inject the `java.time.Clock` bean from `common/TimeConfig` into any
business service that needs the current time. Use `LocalDateTime.now(clock)`
for Singapore local date/time, `LocalDate.now(clock)` for Singapore dates, and
`clock.instant()` or `Instant.now(clock)` for timestamps. Persist timestamps as
instants; do not replace them with local date/time values.

Never call a no-argument `now()`, `System.currentTimeMillis()`, or create another
system Clock in business code. `TimeConfig` is the single source of system time,
so a future DemoClock can replace it without changing services. Tests should
inject `Clock.fixed(instant, ZoneId.of("Asia/Singapore"))` when time affects behavior.

The main-code review on 26 September 2026 found that the price and weather
services use the injected Clock and no direct system-time calls remain outside
`TimeConfig`. The [PR checklist](.github/pull_request_template.md) applies this
rule to future changes. Feature-lead acknowledgements are tracked in the
[CSDT4-21 acceptance checklist](docs/jira-alignment.md#shared-clock-adoption-csdt4-21).

## F3: forecaster and baseline evaluator

The [forecast page](/forecast.html) reads saved 24-interval forecasts from Spring.
F3 adds Java baseline fallback, revision-aware history/imports, PostgreSQL forecast
runs, and a separate Python training/inference package under `ml/`. Existing F1
polling and F2 pages remain in place. The standalone collector is unchanged.

Start with the [F3 runbook](docs/forecasting/runbook.md),
[contracts](docs/forecasting/contracts.md), and
[measured results/remaining acceptance work](docs/forecasting/results.md).
The [18 October acceptance report](docs/forecasting/acceptance-2026-10-18.md)
contains the current evidence and remaining dependencies. Official EMC final-price
history now covers June 2025–September 2026. The twelve-month model and 56-day
unseen study are explicitly retrospective; they do not establish what final prices
or future demand forecasts were available historically. Research artifacts remain
unapproved. Live Java baselines continue when Python is absent or invalid.

### Model runtime decision (CSDT4-34)

| Option | Benefits | Costs and decision |
| --- | --- | --- |
| A. Train/run inside Java | One application runtime | Reimplementing the existing scikit-learn training/features changes behavior and adds maintenance. Rejected for this implementation. |
| B. Separate Python inference service | Same fitted pipeline and feature code in training, evaluation and serving; pinned supported libraries | A second local process and bounded HTTP dependency. **Selected; preserves the existing architecture.** Java owns validation, persistence and baseline fallback. |
| C. Python training, exported Java inference | One serving runtime and potentially lower request overhead | Requires an export runtime/dependency and verified parity for 24 HGB models, missing values, holidays and demand. No validated export exists here. Deferred; no parity/performance claim. |

Spring callers keep `ForecastService` and saved forecast endpoints; browsers never
call Python. Python binds to loopback, accepts bounded provenance-bearing schema2
JSON, and loads only trusted immutable bundles with matching feature/calendar
checksums and dependencies. Deploy Java21, PostgreSQL15+ and the dedicated pinned
Python3.12 environment; keep model storage persistent and inference private. The
local reproduction uses real Spring/FastAPI HTTP and Chromium, with native
PostgreSQL16.15 and17.11 verified; the Compose container itself was not exercised. No
cloud deployment or performance benchmark was performed. Python reloads an atomic
approved pointer on readiness/forecast requests; failed reloads return503 and Java
falls back. Promotion and rollback never alter a bundle in place.

Resident `/forecast.html` shows saved forecasts and today's actuals. Protected
`/admin-forecast.html` shows seven-day forecast/actual series and common-pair MAE.
For local/demo use only, explicitly set `WATTLY_FORECAST_LOCAL_ADMIN_ENABLED=true`
on Spring plus private `WATTLY_ADMIN_USER` and `WATTLY_ADMIN_PASSWORD`. Credentials
alone do not enable it; there are no defaults. Keep demos on loopback. Production
uses shared authentication with this fallback disabled; server-side ADMIN checks
remain mandatory. See the auth-owner decision below.

A configurable weekly user-systemd timer, audit/failure inspection, paired
challenger validation, reload and rollback are documented in the
[runbook](docs/forecasting/runbook.md). Installation is explicit; verification does
not install a permanent timer pointing at the disposable test application.

## F4: recommendations

The dashboard's "Suggested times" card and `/recommendations.html` page suggest the cheapest start for each flexible
appliance (CSDT4-19) and lets the resident accept, pick their own time, or dismiss (CSDT4-20).
Nothing runs automatically. Migration V10 adds `recommendation` and `recommendation_event`.

By default suggestions use a **demo forecast**: the real prices of the 26 Sep 2026 replay day,
labelled as a demo. Set `RECOMMENDATION_FORECAST_SOURCE=f3` to use F3's forecast once it is
actionable. Why, and what's needed first: [docs/f4-recommendations.md](docs/f4-recommendations.md).

## Spending and budget

`/spending.html` shows this month's modelled daily energy use and estimated spending.
`GET /api/v1/households/{id}/budget` returns the current month, daily estimates, and
the projected total. `PUT` to the same path saves `monthlyBudgetSgd` and
`planningRateCentsPerKwh`; migration V11 stores these per household. Fixed-rate plans
use the rate in Household Settings. Price-linked plans need a resident-entered planning
rate before spending can be estimated. Amounts exclude taxes and fixed fees. These
figures use F2's load model, not meter readings or a live retail bill.

Forecasting review updates: [V15 database compatibility](docs/forecasting/migration-v15.md),
[temporary local admin boundary](docs/forecasting/auth-owner-decision.md), and
[verified research archives](docs/forecasting/artifacts.md). Check migration lineage
before upgrading any persistent database; legacy forecast V12/V13 is not automatically
rewritten or repaired.

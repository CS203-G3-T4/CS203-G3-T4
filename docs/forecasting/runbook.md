# F3 setup and operation

Run from the Git root in WSL Ubuntu. Spring and Python run as separate processes
on the same host. Keep the existing `nems-collector.service` and its environment
unchanged. F3 never restarts it or writes its database. See [contracts](contracts.md)
and [measured results and remaining acceptance work](results.md).

## Setup

Requirements: a Java 21 JDK (including `javac`), Python 3.12 with venv/pip,
PostgreSQL 15+ (the existing compose file uses 17), and Node for the optional UI
smoke. Use a project virtual environment, not the collector's Python environment.

```sh
cd /home/bryan/CS203_T4/CS203-G3-T4
python3.12 -m venv .venv
. .venv/bin/activate
python -m pip install -r ml/requirements.lock
python -m pip install --no-deps --no-build-isolation -e ml
cp .env.example .env
# Edit .env for your own database and runtime paths.
set -a; . ./.env; set +a
mkdir -p "$WATTLY_ML_HOME"/snapshots "$WATTLY_ML_HOME"/datasets "$WATTLY_ML_HOME"/models "$WATTLY_ML_HOME"/reports
docker compose up -d postgres
./mvnw spring-boot:run
```

If Ubuntu reports missing `ensurepip`, install its `python3.12-venv` OS package
before creating the venv. Neither Spring nor Python automatically loads `.env`;
source it explicitly in **each** terminal. Environment variables in
[.env.example](../../.env.example) configure runtime paths, DB access and inference.
CLI flags configure individual data/training runs; there is no separate YAML parser.

In another terminal, activate the same venv and source `.env`, then:

```sh
python -m wattly_ml.cli serve --port 8001
curl http://127.0.0.1:8001/health
curl http://127.0.0.1:8001/ready
```

`/health` reports process health. `/ready` returns 503 until a compatible approved
bundle exists. Spring still works with Java baselines. No model is trained by
startup or requests. The forecast page is `http://localhost:8080/forecast.html`.
The public API and Swagger are served by Spring on port 8080; Python binds to
loopback and is not called by the browser.

## Snapshot, prepare and import

```sh
python -m wattly_ml.cli snapshot "$COLLECTOR_DATA_DIR/collector.sqlite3" \
  --kind sqlite --output "$WATTLY_ML_HOME/snapshots"
# Use the exact printed immutable snapshot directory below.
python -m wattly_ml.cli prepare "$WATTLY_ML_HOME/snapshots/sqlite-SNAPSHOT_DIGEST" \
  --output "$WATTLY_ML_HOME/datasets/collector-2026-09-29"
```

The adapter opens SQLite with `mode=ro` and uses its backup API, including WAL
contents, with a 15-second deadline. It never migrates the source. CSV snapshots
reject a file that changes during copying. Destination directories must be outside
the source directory. Manifests include checksums, acquisition time, source path,
coverage, parsing failures, gaps, revision counts and mapping status. Reusing a
snapshot checks its content; preparing an existing dataset directory is rejected.
Interrupted writes remain under a temporary name and are never selected as complete.

For a collector daily CSV use `snapshot FILE --kind combined --output DIR`; this
retains its reduced retrieval provenance. Weather without a genuine retrieval
timestamp is not eligible for operational features. `--provisional-offset-minutes`
on `prepare` supports -60/-30/0/30/60 for investigating mapping; every choice stays
unverified. Nonzero offsets cannot become actionable through the existing floor
mapping's evidence flag.

Inspect `manifest.json` before importing. The bridge is a local startup option,
not an HTTP upload. It validates and imports the entire file transactionally:

```sh
FORECAST_IMPORT_FILE="$WATTLY_ML_HOME/datasets/collector-2026-09-29/prices.jsonl" \
  ./mvnw spring-boot:run
```

Unset the option after importing. Retrying is idempotent. F1's
`MarketHistoryRepository` deduplicates source/period/publication/value/status/
mapping and preserves external IDs and original documents separately. Imported
NEMS rows also use the existing `MarketPriceRepository.upsert`; final EMC labels
never replace live provisional inputs. Collector availability means availability
to that collector, not proof Wattly had the record before import. No hashes are
assumed interchangeable across producers.

## Official historical files

Use EMC's public [Market Data Download](https://www.nems.emcsg.com/nems-prices#market-data-download),
select **Uniform Singapore Energy Price and Demand Forecast**, and download at
most 31 days per file. The actual sample inspected on 29 September 2026 has:

```csv
"INFORMATION TYPE","DATE","PERIOD","USEP ($/MWh)","LCP ($/MWh)","DEMAND (MW)","SOLAR(MW)","TCL (MW)","RUSEP ($/MWh)","MAP ($/MWh)","MAPT ($/MWh)","TPC Applied"
```

`DATE` is `20-Sep-2026`; periods 1–48 map to 00:00 through 23:30 SGT. Only USEP
is the target. The importer also accepts explicit documented Date/Period case
aliases and ISO/slash date forms, and rejects unfamiliar headers/periods. The
tiny matching CSV committed under `ml/fixtures` has **synthetic** values.

The public single-day sample succeeded. The subsequent 2025-04-01–2025-05-01
history request returned **HTTP 403**, so automatic acquisition stopped. Supply
permitted downloaded files to proceed; do not bypass access controls.

```sh
python -m wattly_ml.cli snapshot /path/to/emc.csv --kind emc \
  --source-url 'https://www.nems.emcsg.com/nems-prices' --output "$WATTLY_ML_HOME/snapshots"
python -m wattly_ml.cli prepare "$WATTLY_ML_HOME"/snapshots/emc-* \
  --output "$WATTLY_ML_HOME/datasets/emc-v1"
```

These files have final-price truth, **null** historical publication/availability,
and a `retrospective-final-csv` provenance. `--retrospective` explicitly assumes
period-end availability for historical features. It does not claim the final price
was actually published then. Source and acquisition timestamps are never substituted
for each other. Demand and weather are excluded from v1 model features until enough
matching historical vintages exist. Daily weather revisions are now retained by
the existing Java collector; prior overwritten revisions cannot be recovered.

## Train, evaluate, approve

The following dates illustrate a **future run after obtaining complete files**.
They are not claims that those files are present. Reserve 28 days of warm-up,
12 months of training, a separate validation block, at least eight untouched test
weeks, and an F5 replay day beyond training/selection. Both training and validation
purge the origins whose full target intervals cross their end boundary. Operational
training/selection labels must actually have arrived by their respective cutoff.

```sh
python -m wattly_ml.cli train "$WATTLY_ML_HOME/datasets/emc-v1" \
  --start 2025-05-01T00:00:00+08:00 --cutoff 2026-05-01T00:00:00+08:00 \
  --end 2026-07-20T00:00:00+08:00 --truth-policy FINAL --retrospective \
  --version price-calendar-v1 --output "$WATTLY_ML_HOME/reports/validation-v1"
python -m wattly_ml.cli evaluate "$WATTLY_ML_HOME/datasets/emc-v1" \
  --model "$WATTLY_ML_HOME/models/price-calendar-v1" \
  --start 2026-07-20T00:00:00+08:00 --end 2026-09-20T00:00:00+08:00 \
  --truth-policy FINAL --retrospective --final --output "$WATTLY_ML_HOME/reports/final-v1"
python -m wattly_ml.cli promote price-calendar-v1
```

Freeze model/feature choices before opening the final report. Test results are
never read by promotion. Final evidence also requires 56 days with common eligible
pairs; an insufficient run saves its coverage report and exits with a blocker.
Initial approval requires adequate verified data,
non-exploratory status, common validation coverage, and improvement over the
validation-selected baseline. A candidate that does not pass remains unapproved.
`--exploratory` permits small-data training but its artifact cannot be promoted.
Without an approved ranking, Java fallback order is B1, B2, B3 and is labelled
UNRANKED; after an AI run the ranking is retained in PostgreSQL for outages.

Bundles contain all 24 regressors/imputers, feature order, dependency versions,
seed/parameters, training date/cutoff, dataset digest/quality, validation scores and
baseline ranking. Models and reports use immutable version directories. Load only
trusted local bundles: joblib is executable, and checksums detect corruption rather
than establish trust. Dependency versions must match the lock.

Reports contain per-origin/per-horizon predictions, actuals, exclusion reasons,
frozen spike thresholds/labels, 24 horizon MAEs, three horizon groups, coverage,
spike TP/FP/FN/support and precision/recall. Primary comparisons use identical pairs
eligible for every method. `ownEligibleMae` is a separately labelled descriptive
score on a method's own pairs. Skill is null for zero baseline MAE or missing
common pairs. Weekly block resampling requires at least eight observed weeks;
no-event spike metrics are null. No accuracy/confidence percentages are invented.

## Weekly candidate and rollback

Run manually first. This repository installs no scheduler or training daemon.
Activate the venv, source `.env`, and invoke `sh scripts/forecast-weekly.sh` with
the same dataset/start/cutoff/end/truth/version/output flags as `train`. Its `end`
is a designated **rolling validation** cutoff, never the fixed final-test report.
The job is limited to one process, one computation thread and 45 minutes. It trains
an immutable candidate, scores the current champion on the identical new validation
pairs, and promotes only on improvement. A rejected candidate retains its report
and a failure preserves the current pointer.

`promote` handles initial manual approval. Once a champion exists, use `weekly`
for the required paired challenger comparison. Promotion is serialized with a file
lock and atomically replaces one version/checksum pointer, retaining the previous
version. Rollback is limited to that previous approved bundle:

```sh
python -m wattly_ml.cli rollback PREVIOUS_VERSION
# Restart only the Python inference process after promotion/rollback.
python -m wattly_ml.cli serve
```

Inference loads once at startup; there is no hidden hot reload. Restarting Python
temporarily uses Java fallback and does not restart F1 or the standalone collector.
CLI returns 0 on success, 2 on invalid/unavailable data or artifacts; HTTP readiness
uses 503 and request validation uses 422/413.

## Replay, F4 and deployment

F5 owns replacing the injected Clock. Set `FORECAST_MODE=REPLAY` on its isolated
instance; live ingestion notifications do not trigger replay forecasts. F5 calls
`ForecastJob.runOnce()` after importing its as-of history. Reads exclude both future
origins and future generation times and never mix LIVE/REPLAY runs. Serving rejects
artifacts trained or selected after the simulated origin, including the real artifact
training date. Offline retrospective backtests reconstruct cutoff-frozen models;
they do not prove an artifact existed historically. No qualifying historical replay
artifact has been produced in this implementation session.

F4 must require `ForecastService.latest().actionable()`, then use the existing
`HouseholdDirectory` to exclude fixed-rate households and obtain feasible appliance
windows. `python -m wattly_ml.cli decisions CASES.json --output RESULT.json` accepts
cases with `caseId`, `householdId`, `exposedToWholesalePrice`, equal-length `actual`,
`ai`, `baseline` price vectors, `usualPlanKwh`, and `feasiblePlansKwh`. It compares
realized simulated savings for identical plans, preserves losses, and labels
modelled load and accepted-recommendation assumptions. F4 must supply real cases;
the test case proves mechanics only.

On a server, F1 populates the application's canonical history; inference receives
bounded JSON history from Spring and never needs a collector filesystem. Transfer
immutable snapshots and trusted model directories separately, set `WATTLY_ML_HOME`,
and mount them with the same pinned Python environment. Same-host processes use
loopback. Container deployments must provide a private Spring-to-Python address;
never expose Python as the public browser API. The existing PostgreSQL compose
service and its volume remain unchanged. No cloud deployment was performed.

## Tests and safe recovery

```sh
./mvnw test
. .venv/bin/activate
python -m pytest -q ml/tests
node --test src/test/js/*.test.js
# Full persistence + HTTP + actual page JavaScript smoke; use a disposable DB.
WATTLY_TEST_DB_URL=jdbc:postgresql://localhost:55432/energy_market \
WATTLY_TEST_DB_USER=energy_market WATTLY_TEST_DB_PASSWORD=energy_market \
WATTLY_UI_SMOKE=1 ./mvnw test
```

The DB test creates a new `f3_test_<uuid>` schema and applies V1–V9 there; it does
not alter the application's existing schema. It runs the actual HTML/JS rendering
against live Spring responses using Node's minimal test DOM. It is not a browser
layout/accessibility audit. Without `WATTLY_TEST_DB_URL` that integration test is
explicitly skipped. The Python test suite includes a small offline 24-model
train/save/load/serve smoke, golden parity, cutoff/availability checks, and failed
promotion/rollback checks. Once dependencies are installed it needs no upstream
network or long dataset.

V9 is additive. Existing migrations and API shapes are preserved. To stop new
forecast work during recovery, stop Spring normally; no collector changes are
needed. To revert the application code, retain the added tables for data recovery
rather than deleting forecasts/history. Do not edit an applied migration checksum.
An unapproved/corrupt/missing model leaves saved forecasts and Java fallback intact.

# Forecasting setup, evaluation and operation

Run from the Git root. The independent `/home/bryan/cs203` collector remains
unchanged: snapshots use SQLite read-only backup, never restart/migrate the source.
See [contracts](contracts.md), [acceptance evidence](acceptance-2026-10-18.md) and
[official source provenance](historical-acquisition-2026-10-18.md).

## Install and run

Requirements: Java21 JDK, Python3.12, PostgreSQL15+, Node for tests; systemd user
manager for the optional local timer. Compose uses PostgreSQL17. The acceptance
proof used native PostgreSQL16.15 and17.11, Java21, Python3.12 and Chromium on WSL
Ubuntu; the Compose container itself was not exercised.

```bash
cd /home/bryan/CS203_T4/CS203-G3-T4
python3.12 -m venv .venv
. .venv/bin/activate
python -m pip install -r ml/requirements.lock
python -m pip install --no-deps --no-build-isolation -e ml
cp .env.example .env
# Configure DB_URL/DB_USER/DB_PASSWORD and persistent WATTLY_ML_HOME in .env.
# Optional local/demo admin: explicitly enable WATTLY_FORECAST_LOCAL_ADMIN_ENABLED
# and add private WATTLY_ADMIN_USER/WATTLY_ADMIN_PASSWORD; no defaults exist.
chmod 600 .env
set -a; . ./.env; set +a
docker compose up -d postgres
./mvnw spring-boot:run
# In a second terminal, source the same environment and activate the venv:
python -m wattly_ml.cli serve --port 8001
curl http://127.0.0.1:8001/health
curl http://127.0.0.1:8001/ready
```

Neither process automatically sources `.env`. Python `/ready` is503 until an
approved compatible model exists. This is expected for the delivered unapproved
research model. Java supplies validated baseline fallback, or an explicitly
unranked baseline, or unavailable when contiguous history is insufficient.
Never edit a research manifest to bypass approval.

Resident: `http://localhost:8080/forecast.html`. Admin:
`http://localhost:8080/admin-forecast.html`; the browser requests Basic credentials
only when the local/demo fallback is explicitly enabled. Server-side ADMIN checks
protect the page and APIs. Keep local demos on loopback; production uses shared
authentication with the fallback disabled. Existing trusted ADMIN principals pass;
an authenticated non-admin cannot be elevated by a Basic header.

Price ingestion triggers the coalescing forecast worker after persistence. Import
and first-success/recovery notifications also trigger it when a revision was already
imported. Saved runs/points are immutable. LIVE and REPLAY stay separate. The shared
Spring Clock controls business time. F4 requires `actionable=true`, so stale input,
unverified mapping and unavailable forecasts cannot schedule appliances.

## Acquire, prepare and import

```bash
python -m wattly_ml.cli snapshot "$COLLECTOR_DATA_DIR/collector.sqlite3" \
  --kind sqlite --output "$WATTLY_ML_HOME/snapshots"
# Replace SNAPSHOT with the exact printed immutable directory.
python -m wattly_ml.cli prepare SNAPSHOT --output "$WATTLY_ML_HOME/datasets/collector-v1"
FORECAST_IMPORT_FILE="$WATTLY_ML_HOME/datasets/collector-v1/prices.jsonl" ./mvnw spring-boot:run
```

Unset FORECAST_IMPORT_FILE after import. It is transactional/idempotent and does
not replace provisional live inputs with final labels. Snapshots/datasets/reports
reject overwrite and record checksums, parsing failures, gaps and revisions.
Period-offset investigation remains unverified. Set
`FORECAST_PERIOD_MAPPING_EVIDENCE=EMC_RT_20260929_24_PERIODS` only for the existing
floor mapping documented in [period-mapping-verification.md](period-mapping-verification.md).

Official CSV acquisition now works through EMC's advertised public download form;
see the exact URL, headers, date ranges and audit in the source note. The portable
evidence archive includes original monthly files June2025–September2026. It has
23,376 unique final-price periods. Publication/availability remain null. Prepare
immutable `snapshot FILE --kind emc --source-url URL --output DIR` per file, then
`prepare SNAPSHOT_DIRS... --output DATASET`. Only explicit `--retrospective` allows
the period-end assumption; final-price evidence is not operational replay.

Future operational imports need genuine price vintages for June2025–September22,
2026: aware `periodStart`, `sourceUpdatedAt`, `availableAt`, `usep`, `priceStatus`,
`source`, `revisionId`, `timeMapping`, provenance and original payload. Expected
forward demand uses one JSONL object per actual target/vintage, for example:

```json
{"targetPeriod":"2026-10-19T01:00:00+08:00","publishedAt":"2026-10-18T23:00:00+08:00","availableAt":"2026-10-18T23:02:00+08:00","demandMw":6500,"source":"AUTHORIZED_SOURCE_AND_VINTAGE"}
```

This is a format example, not measured data. Supply via
`prepare ... --demand-forecasts authorized-demand.jsonl`; live Spring import is an
ADMIN POST `/api/v1/admin/forecast-demand` with an array of the same records.
Do not derive vintages/coverage from current demand or the final CSV demand column.
No future target coverage is invented; missing values remain missing. MOM holidays
are versioned and publication-aware; update source/version/digest together in a new
schema-compatible artifact, never edit an approved bundle.

## Reproduce the frozen retrospective study

Reserve fresh immutable output/version names when rerunning. These are the actual
frozen windows, not a proposal. September28 demo stays outside fitting/selection.
Use one computation thread (`OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 MKL_NUM_THREADS=1`).

```bash
export PYTHONPATH="$PWD/ml/src"
export WATTLY_ML_HOME="$PWD/.local/acceptance-2026-10-18/runtime"
data="$PWD/.local/acceptance-2026-10-18/datasets/official-final-v1"
reports="$PWD/.local/acceptance-2026-10-18/reports"
python -m wattly_ml.cli baseline-study "$data" \
  --start 2026-04-01T00:00:00+08:00 --end 2026-07-01T00:00:00+08:00 \
  --retrospective --output "$reports/baselines-rerun"
python -m wattly_ml.cli train "$data" \
  --start 2025-07-01T00:00:00+08:00 --cutoff 2026-07-01T00:00:00+08:00 \
  --end 2026-07-29T00:00:00+08:00 --truth-policy FINAL --retrospective \
  --protected-start 2026-07-29T00:00:00+08:00 --protected-end 2026-09-29T00:00:00+08:00 \
  --version acceptance-rerun-v2 --output "$reports/validation-rerun"
python -m wattly_ml.cli evaluate "$data" \
  --model "$WATTLY_ML_HOME/models/acceptance-rerun-v2" \
  --start 2026-07-29T00:00:00+08:00 --end 2026-09-23T00:00:00+08:00 \
  --truth-policy FINAL --retrospective --final --output "$reports/unseen-rerun"
python -m wattly_ml.cli spike-study "$data" \
  --start 2026-04-01T00:00:00+08:00 --end 2026-07-01T00:00:00+08:00 \
  --retrospective --output "$reports/spikes-rerun"
```

B1/B2/B3 use CSDT4-18's canonical definitions. CSDT4-35 says B3 “last week”;
that conflicting wording is documented, not silently implemented. Rank only
identical eligible pairs. Own-coverage MAE is descriptive, never a fair ranking.
`baseline-validation.json` is independently importable, without AI approval:

```bash
curl --fail --user "$WATTLY_ADMIN_USER" -H 'Content-Type: application/json' \
  --data-binary @"$reports/baselines-three-months-v2/baseline-validation.json" \
  http://127.0.0.1:8080/api/v1/admin/forecast-baseline-validation
# curl prompts for the password; avoid putting credentials into shell history.
```

The saved evidence contains paired MAE for horizons1–6 (next3h),7–12 (3–6h),13–24
(6–12h), coverage/exclusions and frozen same-time-of-day spike references.
Threshold-relative TP/FP/FN are not independently labelled genuine event accuracy;
inspect distinct target and contiguous event counts too. No unsupported accuracy
percentage is supplied. Sensitivity k3/min14/floor1 is recommended for team review,
not approved by the team.

## F2/F4 household comparison

Use a running isolated application populated with genuine F2 project profiles and
the unchanged F4 optimizer. Configure WATTLY_RUN_API_URL and admin credentials.
The protected adapter never uses the live database for acceptance testing.

```bash
python -m wattly_ml.cli savings-study "$reports/unseen-weeks-v1/predictions.jsonl" \
  --validation "$reports/validation-v1/summary.json" \
  --model-manifest "$WATTLY_ML_HOME/models/price-calendar-holiday-demand-v2-20261018/manifest.json" \
  --households 1 2 3 4 5 --output "$reports/household-savings-rerun"
python scripts/verify-forecast-evidence.py .local/acceptance-2026-10-18
```

Use actual F2 IDs returned by `/api/v1/households` in your isolated instance.
The cohort is frozen in proof artifacts. Two preset origins/day and earliest
eligible decision per appliance run prevent overlap inflation. Runs are weighted
runsPerWeek/7. Same feasible windows and actual prices score both schedules, losses
remain signed and fixed-rate households are never shifted. These are **modelled
consumption and simulated savings under current-profile counterfactuals**, not
measured bills or verified historical household behavior. The older generic
`decisions` adapter remains compatible.

## Weekly schedule, audit and recovery

The weekly service requires a real running ADMIN audit API, dedicated runtime,
private environment and an existing user systemd manager. Inference must use the
same persistent WATTLY_ML_HOME. Copy and edit the example; source files contain no
real credentials. The timer defaults to Sunday03:00 Singapore and is configurable.

```bash
cp scripts/forecast-weekly.env.example /tmp/wattly-weekly.env
chmod 600 /tmp/wattly-weekly.env
# Edit paths, actual API credentials and optional authorized history/demand inputs.
# Move it to your private persistent config before installing.
bash scripts/install-forecast-timer.sh render /path/to/private-weekly.env /tmp/wattly-units
bash scripts/forecast-weekly-scheduled.sh /path/to/private-weekly.env
bash scripts/install-forecast-timer.sh install /path/to/private-weekly.env
bash scripts/install-forecast-timer.sh status
systemctl --user list-timers wattly-forecast-weekly.timer
journalctl --user -u wattly-forecast-weekly.service -n 100 --no-pager
curl --fail --user "$WATTLY_ADMIN_USER" \
  'http://127.0.0.1:8080/api/v1/admin/forecast-training-attempts?limit=20'
python -m wattly_ml.cli sync-runs "$WATTLY_ML_HOME"
bash scripts/install-forecast-timer.sh uninstall
```

Do not install the example unedited. `render` verifies units without activating.
`uninstall` disables/stops the timer; retains unit files, immutable artifacts and
audit records for inspection. No persistent acceptance timer points at the disposed
test app. A user manager must remain running for unattended schedules; arrange
host login/lingering under your normal local operations policy if required.

Every attempt writes a durable local record before acquisition/fitting and mirrors
it to `model_run`. Database outage fails training closed and retains `.pending`
spool files; `sync-runs` restores exact final outcomes. Bounded abandoned attempts
are recovered after one hour under the training lock. Acquisition, training,
rejection and promotion failures are recorded, not only successful serving.
Inspect `$WATTLY_ML_HOME/training-attempts/*.json` and reports on failure. Successful
attempts record data identity/ranges and validation scores; no-data failures leave
scores empty with their reason. Protected intervals are removed from training,
labels and lag inputs; missing history reduces coverage, never gets filled.

Native timer resource limits are CPU100%, memory2G, 47min service limit; wrapper
allows45min plus30s termination. One training lock and one numerical computation
thread prevent overlapping candidates. Candidate and champion use identical
validation windows and eligible pairs. Worse, inadequate, failed or unapproved
candidates retain the champion. Official retrospective models and models without
forward-demand provenance cannot pass production approval.

Promotion atomically swaps a checksum pointer under a lock; models remain immutable.
Inference checks it on `/ready` and `/forecast` and reloads safely. Failed/corrupt
reload returns503 so Spring falls back. Rollback selects the previous approved
bundle, also without restarting the collector or Spring:

```bash
python -m wattly_ml.cli promote NEW_APPROVED_VERSION
python -m wattly_ml.cli rollback PREVIOUS_APPROVED_VERSION
curl http://127.0.0.1:8001/ready
```

`promote` never reads protected test results. Once a champion exists use `weekly`
for challenger evaluation, rather than manually bypassing it. The supplied
retrospective research artifact deliberately fails promotion gates.

## Controlled model improvement (research only)

`improve-history` compares fixed long-history and recent schema2 models, including
B2 corrections, on earlier common-pair validation. `confirm-improvement` evaluates
the frozen candidate on later data without fitting or promotion. See the
[October 18 comparison and commands](improvement-2026-10-18.md). Research results
do not change the serving pointer or relax production approval requirements.

`grade-spikes PREDICTIONS.jsonl --output NEW_DIRECTORY` grades saved forecasts for
missed spikes, false alarms and event warning lead time. New improvement studies
and future confirmations include that report under `spikes/`. See the
[spike-focused comparison](spike-grading-2026-10-18.md); price MAE alone does not
grade spike detection.

## Verification and migration safety

```bash
./mvnw test
python -m pytest -q ml/tests
node --test src/test/js/*.test.js
# Full isolated proof; PG_BIN points to installed PostgreSQL binaries.
JAVA_HOME=/path/to/jdk21 WATTLY_PYTHON="$PWD/.venv/bin/python" \
WATTLY_PG_BIN=/usr/lib/postgresql/16/bin WATTLY_STACK_PROOF="$PWD/.local/new-stack-proof" \
NODE_PATH=/path/to/playwright/node_modules PLAYWRIGHT_BROWSERS_PATH=/path/to/browsers \
python scripts/verify-forecast-stack.py
```

The stack script creates its own cluster/DB/schema, tests real Spring/FastAPI HTTP,
then uses an archived unmodified collector payload against its isolated app, loads
saved history/ranking, exercises actual resident/admin pages in Chromium, performs
F2/F4 replay, fires an ephemeral native timer and stops only its own processes.
`--application-only` reruns Java plus focused Python acceptance checks before the
application proof; it skips the full Python/frontend suites, whose earlier results
must be supplied separately. Browser prerequisites for the October19 verification are pinned Playwright1.58.2
and its Chromium installed outside source. No live DB is used. Credentials in proof
`private-*.env` files are mode600 and excluded from delivery.

V1–V9 remain unchanged; V10 is the original F4 recommendations migration from its
cached branch; V11 is main’s unchanged household-budget migration; V12 is main's recommendation refresh; V13 is main's recommendation spike warnings; V14 is main's recommendation confidence; V15 adds operation/ranking/demand/audit support. Both fresh
migrations and PostgreSQL persistence are covered by the isolated proof. To roll
back code, stop Spring normally and retain expanded tables; never edit applied
migration checksums or delete saved predictions. The source/evidence delivery
includes the October modules/tests. See [V15 compatibility](migration-v15.md) before upgrading an existing database.

## Review correction: local auth, artifacts and recovery

Forecasting's local Basic fallback now requires `WATTLY_FORECAST_LOCAL_ADMIN_ENABLED=true`
**on Spring**, plus private `WATTLY_ADMIN_USER`/`WATTLY_ADMIN_PASSWORD`. Merely
setting credentials no longer enables it. Disable the flag with shared auth;
[the auth-owner decision](auth-owner-decision.md) lists exact routes and the
remaining production machine-client/CSRF contract. Run local demos on loopback.

[The artifact guide](artifacts.md) replaces tracked generated reports with a
reproducible, verified archive. The default public demo is a small projection of
real saved results, not a synthetic replacement or the latest live AI model.

F1's `MarketObservationSaved` is a post-save availability notification: after
canonical rows **and ingestion status** are persisted, a changed revision or the
first successful poll after absent/non-success status can make saved/imported
history usable. A successful duplicate recovery therefore notifies forecasting
and main's recommendation worker once; successive successful unchanged polls do
not. A listener failure cannot undo F1's saved data. The tests cover imported
duplicate startup, failure recovery and repeated successful duplicates. This
semantics is preserved for F1-owner review; no owner acknowledgement is asserted.

The weekly path accepts an explicit aware `now` in Python tests. Its one captured
scheduling instant defines the Singapore-midnight default validation end and UTC
version timestamp; validation/training overrides remain explicit. `scheduledAt`
is recorded separately from actual execution `trainingDate`/provenance timestamps.
Historical replay origins/cutoffs and training-only transformations are unchanged.

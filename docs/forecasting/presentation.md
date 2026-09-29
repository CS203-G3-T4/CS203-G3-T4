# Standalone F3 presentation — 30 September 2026

**Experimental — limited training data.**

The presentation combines an immutable offline AI experiment with the existing
live Spring forecast and Java fallback. It needs no F4 households, scheduling,
F5 application replay or login. The admin accuracy endpoint remains protected.
The offline experiment is not an approved production model.

## Start on this WSL machine

The prepared runtime, tools, database, snapshots and model are outside Git at
`/home/bryan/CS203_T4/wattly-f3-demo`. The Java 21 JDK, PostgreSQL 16 binaries and
dedicated Python 3.12 environment are under its `tools/` directory, so starting
the demo does not depend on temporary test tools under `/tmp`.

```bash
cd /home/bryan/CS203_T4/CS203-G3-T4
source /home/bryan/CS203_T4/wattly-f3-demo/runtime.env
./scripts/forecast-demo.sh start
```

Open **http://localhost:8082/forecast-demo.html**. The ordinary forecast page is
http://localhost:8082/forecast.html. Spring binds to loopback on 8082, demo Python
on 8002 and the separate demo PostgreSQL server on 55440. Ports are overridable
through `WATTLY_DEMO_PORT`, `WATTLY_DEMO_PYTHON_PORT`, `WATTLY_DEMO_PG_PORT`.

Startup takes a fresh **read-only SQLite backup** for operational history and
imports it through F1. It never retrains or replaces the frozen experiment. It
uses the existing F1 startup/scheduled polling, daily weather integration and
shared real Clock. A local one-shot command polls F1 and runs the existing F3 job
to avoid waiting until the next scheduled half-hour. It adds no public trigger.
The collector and its environment are never started, stopped or modified.

The script explicitly sources the existing `scripts/forecast-period-mapping.env`.
Its evidence ID is `EMC_RT_20260929_24_PERIODS`. Other deployments retain the
existing fail-closed default unless their configuration supplies evidence.

```bash
./scripts/forecast-demo.sh status
./scripts/forecast-demo.sh refresh  # One F1 poll and F3 calculation; no training.
./scripts/forecast-demo.sh stop     # Only this demo's processes and database.
```

Logs and PID/start-time records are in `$WATTLY_DEMO_HOME/run`. The script checks
process identity before stopping it. The database uses the `f3_presentation`
schema in its own local instance. Demo database authentication is local trust;
the instance binds only to loopback. No production DB settings are loaded.

## Four-minute walkthrough

1. **Purpose and limitation.** Read the experimental label. Explain that the
   question is whether a small price/calendar model improves a simple baseline.
   This is one held-out day, not a validated claim of general accuracy.
2. **Show the comparison.** Point to the three MAE cards and the results table.
   AI lost to B1 overall. Change the **offline forecast origin** to show actual
   prices, AI and B1/B2 predictions across the same 24 future half-hours. This
   changes the selected saved experiment, not the application's Clock.
3. **Show the live boundary.** Scroll to live price and fallback. Show SGT source
   publication time, forecast origin and the 24 target starts. The experimental
   model is unapproved, so live forecasts use an eligible Java baseline. The
   live baseline remains labelled unranked: one-day experimental selection was
   not injected into production's model registry.
4. **Demonstrate resilience.** Use the outage commands below. Spring keeps
   returning a baseline while demo Python is down. Restore Python. Explain why
   readiness remains 503: no experimental model was promoted to bypass the gate.

### Outage and recovery

In the same configured terminal:

```bash
curl -i http://127.0.0.1:8002/health  # 200: process healthy
curl -i http://127.0.0.1:8002/ready   # 503: no approved production model
./scripts/forecast-demo.sh python-stop
./scripts/forecast-demo.sh refresh
# Click “Refresh live display” on the page; inspect the BASELINE identity and times.
./scripts/forecast-demo.sh python-start
./scripts/forecast-demo.sh refresh
curl -i http://127.0.0.1:8002/health  # 200 again
curl -i http://127.0.0.1:8002/ready   # Still 503, correctly
```

This proves baseline availability during a real Python outage. **Do not describe
it as an approved live AI switching to baseline:** the unapproved experiment is
shown separately, and the live view uses baseline before, during and after the
outage. The standard API's stale-data, insufficient-history and actionable guards
are unchanged. If the upstream feed is unavailable or stale, show that state;
the saved offline experiment remains usable without an upstream connection.
The page's refresh button only reads Spring responses; it does not run a job.

## Actual experiment results

Snapshot acquired **29 September 2026, 16:44:04 SGT**, from a consistent read-only
SQLite backup. Coverage: **23 September 15:00–29 September 16:30 SGT**, with
**300 payload/revision rows, 289 periods, 11 revision rows, three missing periods,
zero exact duplicate rows and zero parsing failures**. The gaps are 23 September
17:30, 18:00 and 18:30. There are no availability timestamps before publication;
observed publication-to-availability delay ranges from 36 to 1,842 seconds.

The mapping verifier was rerun against this snapshot: 24/24 official table prices
and rounded demand values match the floor rule; ±30-minute alternatives each
have 0/23 joint matches. This covers the documented live observation window,
not every historical revision or final settlement price. Frozen manifests keep
their original conservative `UNVERIFIED_FLOOR`/`mappingVerified=false` metadata;
the evidence is recorded separately and configured for Spring. No past report
was edited to manufacture verification or model eligibility.

All boundaries below are midnight SGT; end times are exclusive.

| Use | Dates | Eligible origins |
| --- | --- | ---: |
| Input warm-up | Available history from 23 September | — |
| Train | 24 September → 27 September | 120 per horizon; 24 origins purged |
| Validate and choose baseline | 27 September → 28 September | 24 |
| Untouched test | 28 September → 29 September | 24 |

The existing 24 direct gradient-boosted models use the shared 16 price/calendar
features, 40 iterations, 15 leaves and seed 42. No hyperparameter search or random
time split was used. Imputers fit training examples only. The trainer receives
only rows available before the test day and applies the earlier training cutoff
to training labels. Validation selected B1 before test results were calculated.
Test inputs at later origins may use earlier observations from that same test day;
there is no refitting on them. Truth is the latest provisional revision published
and available by each split end, not final EMC settlement truth.

| Method | Validation MAE | Test MAE | Test eligible origins | Test eligible pairs | Excluded pairs |
| --- | ---: | ---: | ---: | ---: | ---: |
| AI | 396.02 | **273.55** | 24 | 576 | 576 |
| B1: mean of six recent contiguous periods | **311.11** | **234.31** | 24 | 576 | 576 |
| B2: yesterday's target period | 581.00 | **344.31** | 24 | 576 | 576 |
| B3: mean of seven prior matching periods | unavailable | unavailable | 0 | 0 | 1,152 |

MAE units: **SGD/MWh**. AI's test error is **39.24 SGD/MWh higher than B1**;
skill against the validation-selected baseline is **−0.1675**. AI beats B2 on this
day but does not beat the selected baseline. This outcome does not support model
promotion.

| Test horizon group | AI MAE | B1 MAE | B2 MAE |
| --- | ---: | ---: | ---: |
| 1–6 | 166.43 | 193.71 | 207.17 |
| 7–12 | 273.06 | 267.21 | 305.49 |
| 13–24 | 327.36 | 238.16 | 432.29 |

The primary comparison uses the **same 576 pairs for AI/B1/B2**, chosen by a rule
that includes baselines with eligible validation pairs. B3 has insufficient
seven-day history. The all-method common set including B3 is explicitly **zero**;
its scores are not silently replaced with a relaxed B3 formula.

There are 48 candidate origins on each evaluation day. Origins 12:00–23:30 are
purged because their 24-target span crosses the day's end: 24 origins × 24 targets
= 576 excluded pairs per method. B3 also excludes the other 576 pairs for missing
history. The 576 compared pairs contain only **47 distinct actual target periods**
and overlap heavily. All 48 actual prices are displayed; the midnight period is
not a future target of a midnight-or-later origin. No one-step substitute was
needed, and the production 24-horizon contract is unchanged.

The model was trained on 29 September. This is a retrospective experiment with
as-of-valid inputs, not proof the artifact existed on 28 September. Spike
assessment is unavailable with fewer than 14 matching reference days. One test
day cannot support eight-week uncertainty, robust spike accuracy or general AI
skill claims. Short-horizon results are exploratory as well.

Machine-readable summary: [presentation-results-2026-09-29.json](presentation-results-2026-09-29.json).
The immutable complete run is at
`$WATTLY_DEMO_HOME/experiments/2026-09-29`, containing the recorded split
protocol, model bundle, validation/test records and `public/forecast-demo.json`.
Only that `public` directory is exposed through Spring's static resource support.
The artifact stays exploratory with `productionEligible=false` and no `current.json`.

Dataset SHA-256: `b0797a3dfd148a8368507e08a64600154a1d660075aaaebfcd10fa6bc5bb5502`.

## Reproduce or inspect

Use a **new** output directory; existing experiment directories are immutable:

```bash
source /home/bryan/CS203_T4/wattly-f3-demo/runtime.env
source scripts/forecast-period-mapping.env
OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 "$WATTLY_DEMO_PYTHON" -m wattly_ml.cli demo-report \
  "$WATTLY_DEMO_DATASET" --output "$WATTLY_DEMO_HOME/experiments/reproduction-1"
```

To acquire a new training dataset, use the existing `snapshot` and `prepare`
commands in [the runbook](runbook.md); do not train directly against growing
collector files. `demo-report` reserves the latest elapsed SGT calendar day from
the snapshot acquisition date, uses the previous day for validation and starts
training on the first full observed calendar date. It fails explicitly if the
existing 24-horizon experiment lacks training or common evaluation pairs.

For a fresh environment, follow the runbook's Java 21/Python 3.12/PostgreSQL setup,
install `ml/requirements.lock` and the editable `ml` package, build with
`./mvnw package`, and set the executable/runtime paths in an explicitly sourced
environment file. The prepared local file contains no collector credentials.

## Acceptance and checks

**Completed F3 demo requirements**

- Standalone presentation view, actual price chart, AI/B1/B2 predictions, paired
  metrics, exclusions and explicit experimental/unavailable labels.
- Fresh read-only snapshot, timestamp/gap/revision audit, reproduced mapping
  evidence and its existing Spring configuration.
- Chronological 24-model experiment with a reserved test day and an honest loss
  against B1; no production approval or safety-gate bypass.
- Live F1 price display, 24 correctly timed baseline intervals, actual demo Python
  outage/recovery, protected admin endpoint and independent startup controls.
- **97 Java tests passed without skips; 12 Python tests passed; all three Node
  test files passed.** The runnable Java 21 jar builds successfully.
- Real Chromium desktop/mobile checks passed: four chart series, origin selection,
  24 offline and 24 live rows, no JavaScript errors or page overflow, admin 403.
  Proof and screenshots are under `target/forecast-demo-*`.
- The outage proof is `$WATTLY_DEMO_HOME/verification/outage.json`: Python restored
  to health 200/readiness 503, Spring PID unchanged throughout the outage, and
  collector PID **9572**, active since **23 September 19:33:41 +08:00**, unchanged.

**Unproven AI accuracy**

- General improvement over statistical baselines; AI loses to B1 in this test.
- Eight-week held-out performance, uncertainty estimates, spike precision/recall
  and performance on final settlement truth or long demand/weather histories.

**Deferred integrations**

- F4 household comparisons, appliance scheduling and realized savings.
- F5 shared application replay and historically existing replay artifacts.
- Authentication implementation. Existing ADMIN role protection remains; the
  presentation reads offline reports rather than opening the admin endpoint.

# F3 implementation results — 29 September 2026

F3's data pipeline, baselines, training/evaluation code, internal inference service,
Spring integration and forecast page are implemented. **Full F3 acceptance is not
claimed:** production period alignment and the requested held-out model evidence
remain unverified. No approved production AI bundle was created or deployed.

## Phase acceptance

| Phase | Working functionality | Remaining acceptance work |
| --- | --- | --- |
| 1 — audit/contracts | Clean starting `main` at `4077fe1`; feature branch; actual Spring Boot 4.1.1/Java 21/JDBC/PostgreSQL/static frontend; no applicable AGENTS.md; shared Clock and F1/F2 seams reused | Team acknowledgements, CSDT4-11 period attribution |
| 2 — adapters/quality | Read-only SQLite backup including WAL; combined CSV and real EMC header support; immutable snapshots/datasets; aware timestamps; revisions, missing intervals and provenance; transactional/idempotent Java bridge | Obtain the long EMC history after the public history request returned 403; independently verify NEMS mapping |
| 3 — baselines/backtest | B1–B3, common Java/Python golden fixture, exact timestamp lags, purged rolling origins, saved predictions/exclusions/coverage | Seven-day B3 and a common comparison set are unavailable in the collector snapshot |
| 4 — AI/spikes | Shared features; 24 direct gradient-boosted regressors; train-only imputation; immutable bundles/checksums; frozen median/MAD spike thresholds; horizon/group scores and weekly block resampling | Real-data training/validation, empirical spike parameters, eight untouched test weeks and AI skill evidence |
| 5 — serving/integration | Load-once FastAPI health/readiness/forecast; bounded validated inputs; Java timeout fallback; atomic saved runs; new-observation trigger/coalescing; mode/Clock safeguards; public endpoints and UI | Production readiness stays false while period mapping is unresolved; ADMIN endpoint awaits the team's authentication layer |
| 6 — operations/evaluation | CLI, locked/bounded weekly candidate workflow, validation-only promotion, previous-model rollback, runbook, F4 decision comparison adapter | Real F4 feasible cases/households and realized simulated savings; F5 orchestration and a genuinely prior artifact; final report when sufficient data exists |

## Actual data coverage and exploratory results

The immutable snapshot was acquired at **2026-09-29 00:55:33 SGT**. It contains
266 distinct payloads from **23 Sep 15:01 through 29 Sep 00:31 SGT**. Under the
explicitly unverified floor mapping this yields **257 periods**, **9 revision
rows**, **3 missing half-hours**, **0 exact duplicate rows**, **0 parsing failures**
and **0 observations first collected more than 40 minutes after publication**.
This does not establish uninterrupted collector uptime or correct period attribution.

Snapshot SHA-256:
`3f7ffd687b9bfaf0e2923d2175766473aca2657a558dbef775d7dbde4e02ce39`.
Canonical dataset SHA-256:
`274b1bc930061fc4919e05a2191d0b47257773edbb528109cdc27425143a4919`.
The [manifest](collector-manifest.json) and [machine-readable scores](collector-baseline-summary.json)
are committed summaries; raw data is outside Git.

An exploratory backtest used 260 half-hour origins from 23 Sep 15:00 through
29 Sep 01:00 SGT (end exclusive), actual retrieval availability, latest provisional
truth in the frozen snapshot, no gap filling, and split-end target purging.

| Method | Origins with eligible pairs | Excluded origins | Eligible pairs | Coverage | MAE on its own eligible pairs (SGD/MWh) |
| --- | ---: | ---: | ---: | ---: | ---: |
| B1 — latest six contiguous known periods | 222 | 38 | 5,328 | 85.38% | 289.067 |
| B2 — yesterday's target interval | 181 | 79 | 4,344 | 69.62% | 384.633 |
| B3 — same interval over seven days | 0 | 260 | 0 | 0% | unavailable |

**The common B1/B2/B3 set is empty.** These separate MAEs have different coverage,
are exploratory and are not a baseline ranking. Primary comparison MAEs and AI
skill are null. There are no assessed spike pairs because even the minimum 14
same-half-hour reference days are absent. Eight-week uncertainty, AI improvement,
spike precision/recall and household savings have not been demonstrated.

The official single-day EMC sample for 20 Sep was inspected for actual headers.
It is outside the collector's overlap and therefore does not verify NEMS period
alignment. The larger public request for 2025-04-01–2025-05-01 returned HTTP 403;
acquisition stopped. The importer and documented manual acquisition path are ready.
Final EMC files without publication vintages can support **retrospective final-price**
evaluation, not an exact reconstruction of live provisional-price availability.

At the initial checkpoint, `nems-collector.service` was active,
PID **9572**, with its original **23 Sep 19:33:41 +08:00** activation time. Its
database had naturally grown to **275 payloads**, latest **29 Sep 05:01 SGT**.
No collector code, environment, service, SQLite tables or raw CSVs were modified.
The continuation rechecked the system service on 29 September: it was still active
with that same PID and activation time. The frozen evaluation dataset is unchanged.

## Verification performed

- **94 Java tests passed**, with no failures or skips, including existing F1/F2 tests. The final acceptance
  runs used Java **21.0.12.1**, Maven **3.8.7**, and an isolated PostgreSQL **16.15**
  instance on port 55439. The project still targets Java 21 and its compose file
  still uses PostgreSQL 17. No H2, Java upgrade or production dependency was added.
- **11 Python tests passed** with pinned Python **3.12.3** dependencies. These
  include a labelled synthetic 24-model training/save/load/inference path and
  approved-pointer promotion/rollback mechanics. Synthetic validation metadata is
  used only to exercise approval mechanics, never as performance evidence.
- **2 Node test files passed**, including the existing escape test. The integration
  smoke fetched live Spring JSON and the actual forecast page script, rendered all
  **24 rows** into a minimal test DOM, and exercised stale/unavailable/network states.
  `target/forecast-ui-proof.json` records the rendered count, BASELINE identity and
  fixed replay origin. The continuation also ran a real FastAPI process with 24
  tiny trained models, verifying Spring → FastAPI → PostgreSQL → public API → page
  JavaScript. It rendered 24 AI rows, then 24 B2 fallback rows after Python stopped
  and new input arrived. Proofs are `target/forecast-python-ui-proof.json` and
  `target/forecast-python-fallback-ui-proof.json`. The model's approval and prior
  training-date metadata are explicitly synthetic test fixtures. This is not
  historical accuracy evidence or a full browser visual audit.
- Timestamp/midnight boundaries, delayed publications, changing revisions, missing
  intervals, exact lags, shared baseline parity, negative/zero prices, zero MAD,
  missing reference history, future/expired weather, train-only imputer fitting,
  training/validation outcome cutoffs and target purging are exercised.
- Missing/corrupt/incompatible artifact guards, invalid/future input, response
  units/targets, Python timeout/down, saved baseline/AI identities, retry deduplication,
  updates during an in-flight run, stale feed, Clock-controlled replay, ADMIN denial,
  atomic partial-run rollback, evaluation readback, immutable predictions, daily
  weather revision retention, and idempotent/transactional imports are exercised.
- A changed manifest under an existing model version now rejects the AI transaction
  and saves Java fallback using the prior accepted ranking. The persistence test
  verifies that even a duplicate run checks model identity, retains the original
  manifest and stores no rejected AI candidate. The page identifies the fallback.
- Python backtests and serving now read the artifact's validated `spikeConfig`;
  Spring rejects AI if its configured rule differs. Tests cover nondefault settings,
  invalid values and baseline fallback without the rejected AI's ranking/flags.
- Editable Python package installation and a runnable Spring Boot jar build passed.
  `git diff --check` passed; new business code uses the shared Clock.

One Python warning comes from Starlette's deprecated AnyIO `BlockingPortal` alias;
it does not fail the tests. The lock records the tested versions.

## Changed areas and safe handoff

`forecast/` owns coordination, baseline/spike calculations, persistence, HTTP client
and public contracts. F1 gains `MarketHistoryRepository`, `MarketHistoryImport`,
`MarketRevision`, and a post-save event; daily weather adds revision retention.
V9 adds canonical revision/provenance, weather revision, forecast/model/evaluation
tables. Existing migrations V1–V8 were not edited. `ml/` contains the offline and
internal serving pipeline, lock, synthetic fixtures and tests. Static HTML/JS adds
the forecast page and links in existing navigation. `.env.example`, the weekly
script and this documentation provide operation commands.

The immutable snapshot, canonical dataset and detailed prediction report were
preserved outside the Git repository at:

```text
/home/bryan/CS203_T4/wattly-f3-runtime/
  snapshots/sqlite-3f7ffd687b9bfaf0e292/
  datasets/collector-v1/
  reports/collector-final/
```

No scheduled training daemon, cloud deployment, paid service, F2/F4/F5 replacement,
or production model approval was performed. The code is at a tested, runnable
checkpoint. The temporary FastAPI process, its fixture bundle and the disposable
PostgreSQL server were torn down after testing. No background training or partially
promoted model remains. Setup,
import, train, serve, test, deployment and recovery commands are in the
[runbook](runbook.md). Production use must retain the current non-actionable mapping
guard until independent EMC evidence is recorded.

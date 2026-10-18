# Current forecasting contracts — 18 October 2026

This section supersedes the historical checkpoints below. See the
[acceptance report](acceptance-2026-10-18.md) and [runbook](runbook.md).

- `ForecastPredictor` names each baseline and returns24 points or unavailable.
  B1 is the latest six contiguous known half-hours; B2 is the target yesterday;
  **B3 is the target's average over the previous seven days** (CSDT4-18).
  CSDT4-35's “same time last week” conflicts with that parent definition; it is
  not silently substituted for B3. Existing `BaselineForecastService.calculate`
  and `ForecastService` callers/endpoints remain compatible.
- Price inputs require period, source publication and availability <= origin.
  Operational truth is a saved revision. Final CSVs with missing vintages require
  explicit `--retrospective`, labelled RETROSPECTIVE_FINAL_PRICE. No target gaps
  are filled. Chronological train/validation/test targets cannot cross split ends.
- Active feature schema2 retains recent prices and calendar features and adds
  Singapore holiday, origin-known target demand and its availability indicator.
  `features` is shared by fitting/backtest/serving; fitted imputers belong to each
  training pipeline. Legacy schema1 research remains supported explicitly.
  Feature order, schema, MOM calendar version/digest, dependency and artifact
  integrity checks reject incompatible bundles. Calendar source/version lives in
  `ml/src/wattly_ml/resources/sg-public-holidays.json` (MOM2025–2027).
- Spring sends schema2 `expectedDemand`: targetPeriod, publishedAt, availableAt,
  demandMw, source. Timestamps must be aware; target half-hours must align;
  publication <= availability <= origin. No target outside actual vintage coverage
  is extrapolated from current observed demand. Missing demand remains missing.
  Offline `prepare --demand-forecasts FILE.jsonl` uses the same shape and persists
  sidecar checksum/combined dataset identity. Protected admin import is
  POST `/api/v1/admin/forecast-demand`. Reusing a vintage with changed value fails.
- Independent immutable baseline evidence is POSTed to
  `/api/v1/admin/forecast-baseline-validation`; scores must share eligible pairs.
  It can be persisted without AI approval or service success. Java chooses the
  highest ranked baseline with all current inputs; no validated ranking means
  `BASELINE_RANKING_UNRANKED`. Previously accepted AI rankings remain compatible.
- Resident GET `/api/v1/forecast/today` reads saved canonical actuals today as of
  the shared Clock. `/latest` and `/current-assessment` retain their contracts.
  Current EXPENSIVE is displayed as normal (above typical); SPIKE uses the
  same-time-of-day four-week reference, min14, k3, spread floor1. Positive typical
  supports percentage difference; zero/negative typical uses signed SGD/MWh
  difference, avoiding undefined or inverted percentages. Stale/unavailable is
  explicit. Sensitivity recommendation is not team approval.
- ADMIN authorization protects both admin page and all admin APIs. Local Basic
  authentication is enabled only with both environment credentials; existing
  trusted servlet ADMIN principals work. Accuracy adds chart-ready series and
  fair common-pair MAE, selectable horizon1–24 and provisional-first/final truth.
  Missing actuals stay null. Own-coverage legacy scores remain descriptive.
- Durable `model_run` attempt records are written before work, then finalized as
  SUCCESS, REJECTED or FAILED with dataset/range/scores/reason/promotion decision.
  A local fsynced spool preserves API-outage records for `sync-runs`; completed
  records are immutable. GET `/api/v1/admin/forecast-training-attempts?limit=20`
  supports failure inspection. An abandoned bounded attempt is recovered safely
  on the next sync while holding the training lock.
- F4's unchanged `CheapestStartCalculator` and F2 `HouseholdDirectory` own feasible
  plans. Protected `/api/v1/admin/forecast-savings-replay` uses those contracts,
  equal household/input windows and realized prices, retaining losses and excluding
  fixed-rate shifting. Current profiles replayed historically are counterfactuals,
  not reconstructed historical consumption/configuration.
- Original F4 migration V10 is preserved; additive V12 adds operation tables/audit
  columns. Applied migrations are never edited. Application rollback retains new
  tables and immutable evidence. Inference reload follows atomic approved pointers;
  bad/unapproved/missing bundles fail closed into Java fallback.

---

## Archived earlier contract checkpoint

# F3 contracts and implementation checkpoints

> Update 29 September 2026: the live NEMS floor mapping is now independently verified
> against 24 official EMC periods. See [period mapping evidence](period-mapping-verification.md).
> Earlier mapping-blocked statements below describe the frozen evaluation checkpoint.
> Historical performance and team integration blockers remain.

Branch: `feature/f3-forecaster-baseline`, based on `4077fe1`. The initial working
tree was clean. No ancestor or repository AGENTS.md was found. Stack: Spring Boot
4.1.1, Java target 21, JDBC/Flyway/PostgreSQL, Jackson 3, static HTML/JavaScript.
Keep F1's collector schedules, shared Singapore Clock, existing APIs and F2 intact.
The independent `/home/bryan/cs203` collector is read only for this work.

## Time, truth and safety

`asOf` is one injected Clock instant. Targets are the 24 half-hour starts strictly
after it, even at an exact boundary. B1 uses six contiguous known periods ending
at the current boundary if already published/available, otherwise the preceding
boundary. It cannot slide further back to conceal a gap. B2 and B3 use target
minus 1 day and minus 1–7 days.
An input is usable only if its period, publication and actual availability are no
later than the origin. Revisions are selected by publication then availability;
ties with conflicting values are rejected. Lags use timestamps, never row offsets.
All stored and exchanged timestamps have offsets; calendar features use Singapore.

Collector mapping defaults to `UNVERIFIED_FLOOR` (configurable whole half-hour
offset). Production readiness stays false until F1's CSDT4-11 comparison provides
evidence. EMC final CSVs have explicit periods 1–48, starting at midnight SGT.
Files without publication vintages retain null publication/availability. Using
them as past inputs requires explicit retrospective mode, assuming availability
at period end; such reports cannot claim operational replay fidelity.

No target filling, price clipping, spike removal or fabricated weather. Initial
AI features are price and calendar only. Daily weather revisions are retained
going forward; preexisting overwritten revisions cannot be recovered. Spike
thresholds use prior days at the target half-hour, frozen at origin. Default
28-day window, minimum 14 samples, k=3, spread floor 1 SGD/MWh are provisional
configuration pending validation. No probabilities or confidence percentages.
Python backtesting and inference use the immutable artifact's `spikeConfig`.
Spring accepts AI only when its minimum samples, k and spread floor match that
configuration. A mismatch uses Java fallback. The 28-day window is fixed.
Current assessment uses the same reference: SPIKE above the spike threshold,
otherwise EXPENSIVE above typical + band × spread, CHEAP below typical - band ×
spread, and NORMAL within those bounds. The classification band defaults to 1
and affects current assessment only.

## Ownership and integration

F1 owns canonical market revision storage and imports. Python creates immutable
snapshots and JSONL exports; Java imports via the F1 repository. Python never
writes application tables. Existing ingestion emits an event after saved data
and poll status; a bounded, coalescing F3 worker forecasts without extra polling.
Complete runs and all eligible candidates are saved in one transaction.

Spring owns `/api/v1/forecast/latest`, `/current-assessment`, and
`/api/v1/admin/forecast-accuracy?days=7`. GETs only read saved data. The repository
has no roles/login yet: the admin endpoint must deny requests unless the servlet
principal has role ADMIN, allowing the future authentication layer to supply it.
LIVE and REPLAY are separate; queries exclude origins and generation timestamps
after the Clock. A replay artifact must have completed training AND validation
before the origin, including the artifact's actual training date. F5 owns Clock
replacement and replay orchestration.

F4 should consume `ForecastService.latest()` and require `actionable=true`, then
use the existing `HouseholdDirectory` for exposure and appliance windows. F3
provides a decision comparison adapter accepting identical feasible plans and
actual price vectors. This does not implement F4's scheduling or accepted runs.

Python is optional at runtime. Java B1/B2/B3 remain available on failure. Before
validation ranking exists, default order B1, B2, B3 is explicitly UNRANKED. A
baseline is never labelled AI. Missing history yields UNAVAILABLE. Stale feeds
and unresolved mapping prevent actionable recommendations; saved older runs
retain their timestamps and are visibly stale.
Reusing a model version with a changed manifest rejects the AI transaction,
including on retries, and saves baseline fallback using the prior accepted
ranking. The original manifest and forecasts remain immutable.

## Acceptance checkpoints

1. Audit/contracts: complete; team acknowledgements and CSDT4-11 remain external.
2. Snapshot/import/quality: implemented and verified; long history and mapping blocked.
3. Baselines/backtest/parity: implemented and verified; exploratory collector report saved.
4. AI/spikes/artifacts: fixture-tested; historical performance unproven.
5. Serving/Spring/UI: implemented and verified against isolated PostgreSQL and HTTP.
6. Operations/evaluation: workflows and guards tested; eight-week evidence requires data.

Initial collector audit: 266 distinct payloads, 2026-09-23 15:01 through
2026-09-29 00:31 SGT. Coverage is not proof of contiguous periods. This cannot
satisfy seven-day B3, 28-day references, 12-month development or eight test weeks.
See the final results/runbook for measured coverage and completed checks.

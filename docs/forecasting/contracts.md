# F3 contracts and implementation checkpoints

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

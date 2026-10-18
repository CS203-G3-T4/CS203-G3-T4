> Main sync update: origin/main `941e744` adds household budget migration V11.
> Forecast operations now use the unchanged SQL under V12. The acceptance evidence
> below records the earlier eleven-migration run; the subsequent twelve-migration
> and main-upgrade checks are recorded in `main-sync-2026-10-18.md`.

# Forecasting acceptance delivery — 18 October 2026

Implementation is integrated and independently recalculated evidence is saved.
This report does **not** approve the research model. No commit/push/PR, Jira writes,
live-database tests, collector changes/restarts, paid services or cloud deployment
were performed. The nine parent Jira issues and relevant subtasks were read only;
review findings were checked against current code and local October work.

## Nine-issue checklist

| Issue | Status | Evidence and remaining acceptance condition |
| --- | --- | --- |
| CSDT4-17 | Complete | Existing chronological/as-of harness preserved; handmade-series, cutoff/publication/revision tests; three horizon buckets in saved paired reports. No random split. |
| CSDT4-18 | Complete | Explicit Java predictor contract/adapters; canonical B1/B2/B3; 91-day real EMC scoreboard with warm-up,104,256 identical pairs and exclusions. See baselines.json. |
| CSDT4-33 | Partial | Resident classification/deviation/stale/unavailable implemented; same-slot evening regression and sensitivity evidence. Team agreement and independent genuine-event labels remain unavailable. |
| CSDT4-34 | Complete | README compares A/B/C and retains Python service; real local Spring/FastAPI/PostgreSQL proof; twelve-month fit and56-day unseen retrospective comparison below. No operational-vintage or deployment claim. |
| CSDT4-35 | Partial | Shared schema2 holiday/demand/price/calendar pipeline, provenance guards, compatibility, independent ranking and outage fallback complete. Historical origin-known demand and price vintages absent; research model unapproved and loses July validation to B3. |
| CSDT4-36 | Complete | Actual resident page: saved24 future intervals, today's actuals, spikes, metadata/table/mobile; ingestion/recovery, immutable persistence and fallback tested. Real proof used archived collector payload with unchanged timestamps, only in disposable app. |
| CSDT4-51 | Complete for modelled comparison | Real F2 directory and unchanged F4 optimizer;497 distinct run counterfactuals with identical realized prices. AI loses savings comparison. Historical configurations, adherence and measured bills are not claimed. |
| CSDT4-52 | Complete | ADMIN page/API, common-pair MAE, series, horizon/version/truth/coverage; anonymous denial, actual browser, populated persistence fixture. Fresh real-data app has no settled saved pairs yet; displays unavailable honestly. |
| CSDT4-59 | Partial | Native timer fired with resource limits; durable failed attempts verified in PostgreSQL; rejection/promotion/reload/rollback mechanics tested. Permanent weekly activation still requires a persistent configured runtime and real protected audit API credentials. No timer is left targeting the disposed test app. |

The distinction is deliberate: code/fixture verification does not supply missing
operational data or team approval. Full source/evidence delivery includes the
previously untracked October modules/tests; nothing is staged or committed.

## What changed and why

Spring now owns an explicit baseline predictor contract, independent validated
baseline ranking, demand-vintage persistence, training-attempt auditing and
chart-ready common-pair accuracy. Additive V11 follows the unchanged original F4
V10; applied V1–V9 were preserved. Existing ForecastService callers/contracts and
shared Clock remain. Import plus a first successful/recovered duplicate price poll
now notifies forecasting, closing the startup race without changing collector work.

Schema2 extends the active Python pipeline with versioned MOM holidays and
origin-known target demand. Training, evaluation and serving share feature code;
imputers are fitted only during training. Artifact/schema/calendar/integrity,
freshness, mapping and approval gates remain enforced. Legacy schema1 October
research remains explicit and supported. Atomic pointer reload/rollback keeps one
immutable bundle identity per in-flight request; invalid AI returns503 and Java
selects the best ranked baseline with sufficient inputs. The real-data browser
proof selected B2 because higher-ranked B3 lacked complete current inputs.

Resident/admin charts use saved application data, break missing intervals, allow
negative prices, show expected spikes and retain accessible tables. Positive typical
prices support percentage differences; zero/negative typical uses signed SGD/MWh.
Local configurable Basic authentication supplies ADMIN while server-side checks
remain mandatory; no source/default credentials were introduced.

F4 was integrated from cached teammate commit774de935 using its original17 Java
files, five tests, V10, resources and contracts. Its optimizer was not rewritten.
The protected replay adapter uses F2 profiles and exactly the same feasible-window
calculator for both forecasts. Weekly operations add a private configurable user
timer, bounded runtime/resources, locking, durable attempt spool, DB sync/recovery,
paired champion validation and controlled reload, retaining rollback artifacts.

## Legitimate data and frozen protocol

Sixteen publicly advertised official EMC CSVs provide **23,376 unique half-hours,
487 complete days**, June1,2025–September30,2026. No gaps, duplicates or invalid
prices were found. Original CSVs, response headers/URLs/hashes and strict audit
are in the evidence archive; see [acquisition](historical-acquisition-2026-10-18.md).
They are final settlement exports, advertised D+6 business days. They lack
historical publication/receipt/demand-issue vintages. Period-end input availability
is an explicitly retrospective assumption, never operational replay evidence.

The [frozen protocol](evaluation-protocol-2026-10-18.json) reserves June2025 warm-up,
July1,2025–July1,2026 training, July1–29 validation and July29–September23 test.
September28 demo is excluded. No new hyperparameter search or test-based threshold
choice was performed. Earlier isolated observation exposure is disclosed in the
protocol; model-unseen does not mean complete analyst blindness. The trained
artifact uses17,496 complete origins and purges24 boundary-crossing origins.
Expected-demand coverage is0%; holiday coverage is100%. It stays productionEligible=false.

## Three-month B1–B3 scoreboard

April1–July1,2026, Singapore time,91 origin days, with at least28 preceding days
available. **104,256 /104,832 common pairs (99.4505%)**;576 pairs per method
(24 complete origins) are purged because their target windows cross the split end.
No missing target prices are filled. All three methods have identical coverage.
All errors are **SGD/MWh**.

| Method | All24 | Next3h (1–6) | 3–6h (7–12) | 6–12h (13–24) |
| --- | ---: | ---: | ---: | ---: |
| B1 | 36.3845 | 31.3615 | 37.8133 | 38.1816 |
| B2 | 30.3070 | 30.2903 | 30.2908 | 30.3235 |
| B3 | 30.2272 | 30.2387 | 30.2313 | 30.2194 |

B1 means the latest six known contiguous half-hours; B2 uses the target yesterday;
B3 averages the target half-hour over the previous seven days. CSDT4-35's “same
time last week” conflicts with CSDT4-18; the seven-day-average parent definition
is canonical. B3 ranks first on this study. The initial v1 summary's default
comparator field said B1 despite the correctly ranked B3 import; v2 explicitly
reports the scoreboard winner/ranking, preserving v1 rather than rewriting it.

## Twelve-month model: unseen chronological accuracy

Earlier July validation has31,680 common pairs (98.2143%). B3 was selected using
that window and retained for savings comparison. AI loses that validation comparison.
The protected56-day test has**63,936 /64,512 pairs (99.1071%)**,56 origin days,
2,664 complete origins, and the same576 boundary exclusions per method. No other
method-specific target exclusions occurred. All errors are SGD/MWh.

| Method | Earlier validation | Test all24 | Test next3h | Test3–6h | Test6–12h |
| --- | ---: | ---: | ---: | ---: | ---: |
| AI | 77.4994 | 152.0215 | 147.5332 | 153.1799 | 153.6863 |
| B1 | 104.8820 | 233.0559 | 207.6090 | 249.7941 | 237.4102 |
| B2 | 103.0322 | 198.9415 | 196.6184 | 199.4002 | 199.8737 |
| B3 | 76.9811 | 166.9751 | 166.0420 | 166.6317 | 167.6134 |

AI beats same-time-yesterday on seven of eight consecutive seven-day blocks and
B3 on seven of eight; it loses to both in block3. These independently reconstructed
blocks avoid counting overlapping forecast pairs as independent weeks.

| Block from July29 | Pairs | AI MAE | B2 MAE | B3 MAE |
| --- | ---: | ---: | ---: | ---: |
| 1 | 8064 | 61.0955 | 73.6037 | 65.8798 |
| 2 | 8064 | 76.2784 | 90.0353 | 82.2405 |
| 3 | 8064 | 127.9360 | 122.6032 | 116.1669 |
| 4 | 8064 | 178.3848 | 270.3145 | 184.8990 |
| 5 | 8064 | 131.7205 | 154.0311 | 163.4887 |
| 6 | 8064 | 103.1177 | 132.0223 | 142.7078 |
| 7 | 8064 | 254.4734 | 392.9739 | 289.0046 |
| 8 | 7488 | 293.2533 | 368.0255 | 300.9862 |

These scores support retrospective aggregate superiority over B2, not production
approval or universal weekly superiority. Existing report calendar-week resampling
includes partial edge weeks; no confidence/accuracy percentage is claimed here.

## Spikes, sensitivity and false alarms

The origin-frozen reference uses the same half-hour over the preceding28 days,
median + k × max(1,1.4826×MAD), at least14 samples. A regression test checks that
ordinary evening prices are compared with their own usual evening level.
Development-quarter observed classification assessed4,368 distinct targets:

| k | Distinct alert targets | Contiguous alert events |
| --- | ---: | ---: |
| 2 | 884 | 216 |
| 3 | 658 | 176 |
| 4 | 503 | 134 |

**Recommendation for team review: retain k3, min14, spread floor1.** k2 adds226 alert
targets/40 events; k4 removes155 targets/42 events. This is a workload/sensitivity
tradeoff, not evidence of genuine-event correctness. Team approval is absent.
Independent physical/genuine-spike labels do not exist, so observed classifier
precision, recall and false-alarm counts remain unavailable.

For the test forecast task, threshold-derived labels cover63,936 overlapping
forecast pairs and2,687 distinct targets:445 positive targets grouped into153
contiguous events,2,242 negative targets. Against those fixed rule labels:

| Method | TP pairs | FP pairs | FN pairs | Precision | Recall | Distinct false-alarm targets |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| AI | 1,885 | 3,038 | 8,750 | 0.3829 | 0.1772 | 364 |
| B3 | 1,669 | 3,630 | 8,966 | 0.3150 | 0.1569 | 154 |

Pair metrics describe repeated forecasts, not independent events. Distinct
target/event counts prevent overstating sample support; no independently labelled
genuine-event detection rate is inferred from these self-defined thresholds.

## Household savings: AI loses despite better test MAE

The actual protected F2/F4 API used the five seeded project household profiles,
frozen as current-profile counterfactuals: four price-linked and one fixed-rate
(Rahman, id3). These are modelled project configurations, not measured residents.
Two preset origins/day (00:00,12:00 SGT) yield111 complete shared windows; one
boundary window is excluded. The unchanged F4 optimizer applies identical appliance
power/duration/window/usual-start constraints and SGD0.01 predicted-saving minimum.

Across**497 distinct appliance runs**, usual minus realized scheduled costs,
weighted by runsPerWeek/7, total simulated savings are:

| Forecast | Modelled savings SGD |
| --- | ---: |
| AI | 168.99 |
| Earlier-validation-selected B3 | 176.43 |
| AI minus B3 | **−7.45** |

Costs use real EMC target prices and minute-overlap energy conversion, not forecast
prices. Losses are retained. Fixed-rate shifting is zero and the fixed household
is excluded from wholesale scheduling.724 origin/appliance attempts lack their
usual run inside the shared forecast window; these exclusions are not724 distinct
lost events. Only earliest eligible caseId decisions are counted. Current profiles
held constant, frequency weighting and assumed recommendation adherence are explicit
assumptions; no measured bill-saving or verified historical configuration claim.

The same test forecast MAE/spike comparison above accompanies the savings result.
Lower MAE did **not** produce greater savings. Independent stdlib Decimal arithmetic
recalculated costs, weights, signs and constraints; sub-nanodollar differences from
Java's bounded decimal rounding do not affect the displayed totals.

## Verification and reproducibility

Java: **135 passed,0 failures/errors/skips**. Full Python: **39 passed** (529.75s);
focused tests after the final reload change: **8 passed** (covering40 unique tests
across both runs). Frontend: **4 test files passed** (Node reports4 tests). One upstream deprecation
warning remains; no suite cases were skipped. Final proof inventory is recorded in
[evidence/2026-10-18-acceptance/verification.json](evidence/2026-10-18-acceptance/verification.json).
The isolated PostgreSQL cluster applies all11 migrations, real Spring/FastAPI HTTP
and persistence tests cover AI success/outage fallback and immutable saved runs.
Synthetic approved bundles test promotion/reload/rollback only; they are never
project performance evidence or production approval.

Real Chromium checks resident24 points,10 saved actual periods, admin authorization,
horizon selector, mobile overflow and no JavaScript errors. The newly created real
app has zero settled saved seven-day pairs and displays unavailable. A separate
synthetic persistence browser regression checks the populated admin chart/table.
The native ephemeral systemd timer executes with CPU/memory/runtime limits and
persists a FAILED/RETAINED short-history attempt in PostgreSQL. The manual scheduled
attempt is also recorded. Only disposable databases/processes/units are used.

Full commands for setup, snapshots/imports, frozen training/evaluation, baseline
ranking import, household replay, timer install/status/failure/sync/uninstall and
rollback are in the [runbook](runbook.md). Principal reproduction commands:

```bash
./mvnw test
python -m pytest -q ml/tests
node --test src/test/js/*.test.js
python scripts/verify-forecast-stack.py  # Set JDK/Python/PG/browser paths as documented.
python scripts/verify-forecast-evidence.py .local/acceptance-2026-10-18
bash scripts/install-forecast-timer.sh render /path/to/private-weekly.env /tmp/wattly-units
bash scripts/install-forecast-timer.sh install /path/to/private-weekly.env
bash scripts/install-forecast-timer.sh status
bash scripts/install-forecast-timer.sh uninstall
```

The evidence recalculator verifies every common-pair MAE/bucket, canonical actual,
target mapping, TP/FP/FN, distinct positive/negative counts, sensitivity target/event
counts, eight consecutive weekly blocks and realized household cost/feasibility.
Full per-pair predictions and raw source files are in the evidence archive.

## Exact remaining dependencies and limitations

1. CSDT4-33 needs independent genuine-event labels and actual recorded team agreement
   on k/minimum samples/spread floor. The sensitivity report is a recommendation.
2. CSDT4-35 needs authorized historical provisional price publication/receipt vintages
   and target-specific forward-demand issues actually available at each origin,
   June2025–September22,2026, for operational evidence. Expected formats and import
   commands are in the runbook. Final CSV demand is not a substitute. Test improvements
   cannot override the unfavourable validation result or production approval gates.
3. CSDT4-59's persistent activation needs a private real API credential/configuration,
   persistent runtime and active user manager. Installed files/schedule are concretely
   rendered and native execution verified; no permanent job is left pointing at the
   disposable proof application. Qualified operational data is also required for any
   successful research-to-production promotion.
4. Stronger operational savings claims need historical household/appliance/plan
   snapshots with effective/availability times and resident adherence/consumption;
   current F2 profiles establish only the declared modelled counterfactual.
5. PostgreSQL17 Compose, cloud/container deployment, genuine production promotion,
   long-running unattended weekly operation and a full assistive-technology audit
   were not performed. PostgreSQL16.15/native local deployment and actual Chromium
   were exercised. One upstream Starlette/AnyIO deprecation warning remains.

Initial46 modified/untracked files were backed up before edits; all remain present,
43 byte-unchanged and three extended for integration. Original October research,
including its unfavourable results and protected periods, remains intact. An
unrelated pre-existing `.zip` is preserved but excluded from the source delivery.

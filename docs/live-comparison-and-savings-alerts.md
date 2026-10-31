# Prospective comparison and start-time savings alerts

This records a new, fixed-model live comparison. It does not rerun or improve the historical research result. The existing local solar-assisted model remains experimental. There is no automatic promotion or retraining.

## Fixed protocol

Enable `WATTLY_LIVE_COMPARISON_ENABLED=true` with a unique `WATTLY_LIVE_COMPARISON_ID`, exact `WATTLY_LIVE_COMPARISON_MODEL_VERSION`, SHA-256 `WATTLY_LIVE_COMPARISON_MODEL_SHA256`, and `WATTLY_LIVE_COMPARISON_DAYS=28`. Defaults are disabled. Activation persists the start/end, model and policy once; restarting does not extend the trial. Changing a pinned policy/model under that ID fails. After the window closes, grading outstanding runs continues but new issues are not captured. A deliberate new study requires a new ID and reviewed policy, not resetting existing rows.

Only newly issued LIVE forecasts inside the activation window qualify. AI and B3 must have the same 24 half-hour targets, issue time and spike thresholds. Freshness, availability, version and hash are checked. Baseline fallbacks and exclusions are counted, never labelled AI. Old research predictions and outcomes are not imported into the live trial.

For each eligible price-linked household and flexible appliance, both methods use the same run power, duration, allowed window and usual start, the application's cheapest-start calculator, S$0.05 saving threshold and top-three ranking. Non-selected plans keep the usual start. Already-decided runs are excluded equally. The first eligible plan per household/appliance/usual-start is frozen, including runs-per-week/7 weighting; later forecast revisions cannot cherry-pick a better plan. The evaluation compares hypothetical plans, rather than the resident's eventual choices. It is not a randomised trial or a measure of followed appliance usage.

Both plans and the usual-time alternative wait until all three runs finish and all touched actual prices were observed. Actuals are the first complete provisional prices available to the evaluator; their revision IDs and receipt times are retained and later revisions do not rewrite scores. Missing prices remain pending. Weighting estimates wholesale energy costs if plans were followed: it excludes retail fees, taxes, metered adherence and appliance power variation. Fixed-rate households do not benefit from market timing and are excluded.

Admin-only `/admin-live-comparison.html` and `/api/v1/admin/live-forecast-comparison` report paired price MAE/RMSE, ordinary/spike MAE, harmful reschedules, weighted losses and savings, weekly savings, pending coverage and exclusions. The API also provides each horizon separately. Spike counts are saved run-target pairs with shared observed target periods, not independent onset episodes. Empty scores are pending, not zero error. Do not claim consistent superiority until matched later weeks support it; inspect losses and coverage as well as aggregate savings. The 28-day window may contain too few spikes to establish detection quality.

## Empirical savings range

Savings uncertainty uses whole-run savings residuals per kWh, not price MAE and not the old past-day cheaper-time frequency. Keep the original forecast points and proposal; freeze a separate acceptance baseline using the resident's actual chosen time and run size. A custom time outside original forecast coverage has no range.

Calibration uses the same pinned live trial and AI/B3 method, duration bucket (up to 60, up to 120, or longer minutes) and maximum lead bucket (up to 2, up to 6, or up to 12 hours). Only strictly earlier issue days whose captured opportunities in that bucket all have outcomes available before the prediction are eligible. Multiple correlated rows on a day contribute their maximum absolute savings error per kWh. At least 20 completed days are required; a history with no complete day in the last seven days is unavailable. Use order statistic ceil((n+1)*0.90), scale by this run's energy and retain negative bounds. Later frozen opportunities measure empirical coverage.

This is a conservative empirical target, not a calibrated probability guarantee: days remain dependent, the market changes, custom choices differ from the captured policy, and rare spikes may be absent. Sparse groups may remain unavailable even after 20 calendar days. Do not seed live calibration with synthetic test fixtures or repeatedly tune thresholds on this trial. The resident page explicitly displays warm-up, missing/stale evidence or the range and its limits. The old frequency is labelled 'Past-day pattern', not confidence.

## Near-start rechecks

A server job checks accepted plans every minute from 60 minutes before the chosen start until its run ends. Future periods use a matching fresh saved forecast. The current and earlier periods use actually received current-period prices immediately, never a prediction for an already observed period. Missing or stale inputs produce no change notice.

With a qualified frozen acceptance range, create an in-app notice only when estimated savings are more than S$0.05 beyond its lower or upper bound. The notice asks the resident to review the plan; it does not change a start time or operate appliances. Acknowledgement suppresses unchanged notices. Resolve notices on a return inside the range; a new crossing can reopen one. Stale checks, ended runs and non-accepted plans are hidden. Reads and acknowledgement enforce household ownership and the existing CSRF protection. No email, SMS or paid service is used. Alerts refresh while the Recommendations page is visible, including the Accepted tab. There are no background device push notifications when the page is closed.

The minimum-evidence rule means recording and rechecking can start immediately, while numerical ranges and range-based alerts stay unavailable until enough genuinely new outcomes have accumulated. Before acceptance evidence is recorded, older suggestions show an unavailable range.

## Validation

PostgreSQL tests isolate all synthetic fixtures in unique test schemas. They cover matched truth, immutability after revisions, late-data leakage, fixed model/window, fallback exclusion, warm-up silence, calibrated alert thresholds, deduplication/acknowledgement/reopening, household/admin isolation, custom starts, stale forecasts and current-period actual prices. Pure tests cover daily grouping and negative bounds. V26 expands schema without changing model serving eligibility; V23–V25 are reserved for PR #40's replay migrations. Java/PostgreSQL and frontend suite results plus local activation receipts are documented in the PR.

## Migration coordination

PR #40 at `0c924594` owns `V23__replay_prices_and_household.sql`, `V24__mark_demo_forecast_runs.sql` and `V25__drop_redundant_replay_price_index.sql`. This trial uses `V26__live_comparison_and_savings_ranges.sql`. The disposable PostgreSQL coexistence test applies V1–V22, preserves existing budget data, applies the complete replay V23–V25 set, preserves a demo forecast, then applies only V26. It validates history, checks the replay demo column/index changes and confirms repeated migrations execute no scripts. The replay SQL fixtures are test-only; that feature is not copied into this PR's production migrations.

Delivery is consolidated in PR #48, targeting main. For shared deployment, land PR #40 first, update #48 from main, resolve the Java integration conflicts and recheck the complete source, migration numbers and full CI before merging/deploying #48. Do not deploy V26 and subsequently try to add lower V23–V25 with out-of-order migration disabled. The coexistence test checks SQL compatibility, not a merge of PR40's Java replay feature with the forecasting implementation. No PR was merged automatically.

The private local trial already applied the old V23 trial migration. This source renumbering has not changed its history or running jar. Do not upgrade that instance using ordinary `repair`, deleting tables, resetting its trial, or blindly reapplying this DDL. Its eventual upgrade needs a backed-up, rehearsed history reconciliation to V26 or a separate fresh runtime, preserving the original trial for grading. The earlier V24 reconciliation receipt tested only replay V23 and does not certify the full replay V23–V25 upgrade; it is superseded for rollout planning by this complete migration set. That deployment operation is outside these source-review fixes; the current live collector/model/trial stays untouched.

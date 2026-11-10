# CSDT4-33 and CSDT4-35: remaining story acceptance

All listed subtasks under both stories are Done. The owner requested completion of the parent stories as well. This record distinguishes the remaining original story criteria from completed implementation; it does not reopen subtasks, invent human labels, or mark an unfinished experiment complete.

## CSDT4-33 — Flag genuine price spikes

| Original criterion | Evidence and remaining work |
| --- | --- |
| Compare against the same time of day over the prior four weeks | Implemented 28-day same-half-hour median/MAD reference, minimum 14 observations, k=3, spread floor 1 |
| Ordinary evening peaks are not automatically spikes | Covered by the existing same-slot evening-peak test and reference implementation; this verifies behavior on a known fixture, not empirical genuine-event precision |
| Dashboard shows cheap/normal/spike and deviation from normal | Fresh read-only desktop/mobile check passed: current assessment available, 27 prior-day samples, percentage from typical displayed, 24 forecast points, no browser errors or overflow |
| Check real spikes/false alarms on history and agree sensitivity with the team | Owner already accepted k=3. Statistical sensitivity/reporting scope is complete under subtask 94. Independent human unusual-price labels and Jonathan/team agreement have not been supplied for the broader story criterion |

The prepared blind review covers 4,368 half-hours over 91 days. No independently reviewed labels have been provided to its scorer: genuine-rule precision/recall remains null. The 658 k=3 flags and 176 contiguous flagged runs are descriptive counts, not confirmed spikes or false alarms. Reusing those flags as their own ground truth would make the validation circular.

The reviewer should open the existing blind price-chart workbook, review the actual prices without rule/model decisions, and export their labels with the scope/independence declaration. The existing `scripts/review-spike-rule.py score` command then evaluates k=2/3/4 against those labels. Partial reviews remain explicitly subset-only; uncertain/unreviewed periods are not silently filled. Record the team's actual sensitivity decision when available. No messages have been sent to Jonathan or anyone else; the owner was asked who will perform this review.

Local workbook: http://127.0.0.1:8878/review.html. Portable review ZIP and raw annotations stay outside Git. This service and its existing review data were not replaced or reset. See [the existing review workflow](spike-rule-review-2026-10-30.md).

## CSDT4-35 — AI forecaster v1

| Original criterion | Evidence and remaining work |
| --- | --- |
| Learn from prices, calendar and expected electricity demand | Existing price/solar model serves real forecasts. Separately versioned demand-aware schema 4 and candidate/control fitting are implemented and tested. A real demand-aware model has not yet been fitted; the active schema-3 model intentionally keeps demand missing |
| Honest unseen-week comparison with B1/B2/B3 | Historical solar comparison exists; the separately frozen demand-aware experiment is collecting original issued inputs and must be fitted/scored on its registered later periods |
| Keep the replay demo day out of training | Existing designated demo day is 28 September 2026, inside the protected interval ending 29 September. The frozen solar fit ends 29 July. New candidate origins start 30 October; even their earliest allowed 29-day feature history starts 1 October 15:00 SGT, so inputs and outcomes cannot include that demo/protected interval |
| Best-baseline fallback when AI is unavailable | Existing job/client tests cover Python outage/invalid response, retention of previously validated B3 ranking and stale/unavailable data behavior; full Java suite passes |

At the 30 October 15:29 SGT readiness check, the original-input archive contained 19 complete demand/solar issues. Only one distinct half-hour was inside the registered training cohort, with zero complete 12-hour outcome windows. Multiple updates in that half-hour are not independent training origins. The lack of outcomes cannot be solved by another fitting retry or by substituting later observed demand for an earlier forecast.

The [frozen protocol](demand-candidate-protocol-2026-10-30.json) and its hash are unchanged. It collects training origins until 13 November 15:00 SGT, allows one explicit fit before testing starts on 14 November 15:00, and ends the untouched test on 28 November 15:00. It requires 480 complete origins per split and reports every comparator, including losing weeks. Fit and evaluation remain explicit actions; no timer, new goal or automatic promotion was created.

### Integration gap repaired during this acceptance check

Java had only applied solar-evidence validation and snapshot/run-identity handling to schema 3. The separately prepared demand-aware schema 4 would therefore have bypassed those auxiliary checks and lost its solar input provenance in saved runs if later selected. `PythonForecastClient` and `ForecastJob` now apply the same solar-evidence handling to both schemas. Parameterized tests cover missing/future/duplicate auxiliary evidence and changed-solar run identities for both versions. No candidate or replacement model was activated.

Validation for this repair: **383 Java tests passed**, zero failures/errors/skips, with isolated PostgreSQL persistence tests; **2 frontend forecast/chart tests passed**; the actual read-only desktop/mobile dashboard check passed. The earlier **41 focused Python tests** and full **144-test implementation suite** are recorded in the linked acceptance evidence; Python code did not change in this repair. Test fixtures are not accuracy evidence.

The running jar was not rewritten or restarted. Frozen weights, trial dates, independent collector, weekly retraining state and protected research artifacts are unchanged. Code delivery and human PR approval/merge remain separate.

## Conditions for closure

- Close 33 only after its remaining original validation/team decision is recorded, or after the owner explicitly changes the parent story's acceptance scope. Subtask 94's narrower prior closure is preserved.
- Close 35 when the real demand-aware fit and its honest unseen-period comparison satisfy the original model criteria. Consistently beating every baseline and proving household savings are not extra conditions silently added to this story, although the separate adoption decision still considers price, warning and F4 savings/harm outcomes.

The remaining blockers are human review for 33 and genuinely later received training/testing outcomes for 35. Both parent stories therefore remain In Progress; completed subtasks remain Done.

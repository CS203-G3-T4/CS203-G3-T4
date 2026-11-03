# CSDT4-100 feature-pipeline acceptance

The owner requested completion after clarifying the scope of [CSDT4-100](https://csd-t4.atlassian.net/browse/CSDT4-100). The issue's requirement is its feature-pipeline summary; no additional description or demand-model accuracy acceptance condition was present when checked on 30 October 2026. The acceptance below covers that input pipeline. The parent, CSDT4-35 (AI forecaster v1), retains the separate real-model fitting, evaluation and deployment decisions.

## Requirements checked

| Jira input | Implemented behavior | Verification |
| --- | --- | --- |
| Yesterday, same half-hour | Lookup at target minus one day | Closed-form fixture: 906 |
| Last week, same half-hour | Lookup at target minus seven days | Closed-form fixture: 330 |
| Three-hour mean and spread | Six completed half-hours; mean and population standard deviation | Mean 993, standard deviation sqrt(35/3) |
| 24-hour mean and spread | 48 completed half-hours; mean and population standard deviation | Mean 951, standard deviation sqrt(2303/3) |
| Half-hour of day | Target slot 0–47 in Asia/Singapore | Midnight slot 0 and 11:30 slot 23 |
| Day of week and weekend | Target-local calendar, rather than issue-day calendar | Sunday-to-Monday midnight: weekday 0, weekend false |
| Public holiday | Versioned Singapore calendar, used only after its publication | Monday 10 August 2026 substitute holiday true; existing checks cover ordinary days and unavailable future calendars |
| Feed demand forecast | Latest target-matched vintage published and actually received by issue time | Target 1 = 8000 MW; target 24 = 8023 MW in the fixture; missing/future demand rejection and demand-sensitive HTTP prediction tests |

The new numeric contract test is `test_csdt4_100_requested_inputs_have_known_numeric_values` in `ml/tests/test_demand_candidate.py`. Its expected window values come from closed-form arithmetic progressions, independently of the implementation's mean/standard-deviation calls. It also supplies an extreme current-period price and verifies that the completed-price recipe excludes it.

`ml/src/wattly_ml/features.py` is shared by training, backtesting and HTTP inference. Schema 2 provides the price/calendar/demand inputs. Separately versioned schema 4 combines them with the solar inputs and requires the target's demand forecast. The candidate trainer and `backtest.predict` both call the same schema-dispatched function. HTTP tests use a fitted synthetic demand-sensitive estimator, and the synthetic full fit/control/save/load/held-out-report test verifies that both fit and prediction paths can execute. These are feature-contract tests, not real accuracy evidence.

The live importer (`EmcDemandSnapshotImporter` and its scheduled job) already preserves target, displayed panel publication/update and actual receipt times. Demand is the public grid-wide forecast, not subsequently observed household usage. The existing immutable stored snapshots include real price/solar/demand input provenance. The earlier input audit accepted 16 complete original demand/solar issues; it did not fabricate missing earlier vintages or train a real demand model.

## Validation and scope

- 41 focused feature, solar and acceptance tests passed after adding the explicit Jira-input check.
- The implementation commit `bf2d377` had already passed the complete 144-test Python suite. This closure adds a test and documentation; it changes no runtime code. The focused checks include the new test. No claim that 145 tests were run locally is made.
- The frozen model guard reproduced all 24 saved live predictions exactly, with the model hash and live-trial dates unchanged; see [prior evidence](demand-candidate-evidence-2026-10-30.json).
- Code and evidence are delivered in [PR #42](https://github.com/CS203-G3-T4/CS203-G3-T4/pull/42). Jira closure does not imply independent reviewer approval or merge.

This completes CSDT4-100 on its feature-pipeline scope at the owner's request. It does not assert that the active schema-3 solar model uses demand: its original missing-demand recipe remains unchanged. The separately versioned demand-aware candidate is prepared but has not been fitted to real outcomes. The bounded [demand experiment](demand-candidate-2026-10-30.md), real held-out accuracy/spike results, F4 savings/harm comparison and any later adoption remain work under CSDT4-35. No guaranteed baseline win, production readiness, measured bill savings, timer activation or live-model replacement is claimed.

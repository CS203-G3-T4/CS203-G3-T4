# CSDT4-100: fixed solar + expected-demand candidate

The live expected-demand connection is implemented. The active solar model deliberately leaves its demand feature missing, matching its original fit. Schema 4 is a separate, experimental-only feature policy that uses the most recent target-matched demand vintage with both publication and actual receipt no later than the issue time. It requires all 24 demand targets. Schema 3 and the active model weights remain unchanged.

The [frozen protocol](demand-candidate-protocol-2026-10-30.json) schedules one bounded experiment. It is preparation, not evidence that a demand-aware model is more accurate. No real candidate has been fitted at this point. No goal, new collector, timer, service restart, model promotion or live-trial reset is part of this change.

## Fixed comparison

Fit 24 horizon-specific HGB regressors with the existing 40-iteration/15-leaf/L2=1/seed=42 recipe, early stopping disabled. Fit one solar-only control on exactly the same original issues and outcome pairs. This separates the effect of demand from retraining on newer examples. The frozen 183-day solar model and B1/B2/B3 are additional controls; the shorter refits have less training history than the frozen model, so comparison with the matched solar-only refit is essential.

Use the first eligible original LIVE issue in each half-hour, retaining its actual issue timestamp. A second issue within that slot does not become another training example. Historical demand observed after the fact and later repaired solar values cannot replace the saved issue inputs. A missing target forecast excludes the issue explicitly. Existing archived issues without demand remain excluded.

The code accepts the actual Java stored-row format, projecting canonical revisions into the same HTTP feature contract. Mapping uses the existing `EMC_RT_20260929_24_PERIODS` evidence with a zero-offset source-timestamp check for NEMS, or explicit EMC periods. Original conservative `UNVERIFIED_FLOOR` metadata is retained. See [the evidence and its date/provider limits](period-mapping-verification.md); this change does not claim a new independent mapping verification.

## Schedule (Singapore time)

| Stage | Fixed window |
| --- | --- |
| Training origins | 30 October 15:00 through 13 November 15:00, end exclusive |
| Fit once | 13 November 15:00 through 14 November 15:00, before testing starts |
| Untouched testing | 14 November 15:00 through 28 November 15:00, end exclusive |

Each split purges origins whose 24 target half-hours do not all finish before its end. The training truth is the latest provisional price actually received by the training cutoff. Test truth is similarly frozen at test end. Elapsed time alone is insufficient: all 24 received actuals are required. At least 480 complete, distinct half-hour origins are required for each split. This is an exploratory support floor, not a guarantee of sufficient spike diversity.

The existing app must remain running and saving inputs. Fit is an explicit operator action in the fixed fit window, not an automatically scheduled job. If the window is missed, do not backdate an artifact or silently move the holdout. Record that the prospective schedule failed and explicitly register another protocol before collecting a replacement untouched test period. Existing live-trial dates are unchanged; this separate capture schedule may extend beyond that trial.

## Commands

Export `issues.jsonl` from saved LIVE AI runs with `id`, `asOf`, `mode`, `modelVersion`, `stale`, `status`, and the original `input_snapshot` under `inputs`. Export `prices.jsonl` with `periodStart`, `sourceUpdatedAt`, `availableAt`, `priceStatus`, `timeMapping`, `source`, and `usep`. Retain revision timestamps. Keep generated snapshots, models and predictions outside Git; freeze their hashes with each report.

From the checkout, with the project's Python environment and `PYTHONPATH=ml/src`:

```sh
python scripts/demand-candidate.py readiness \
  --issues /private/issues.jsonl --prices /private/prices.jsonl \
  --protocol docs/forecasting/demand-candidate-protocol-2026-10-30.json \
  --as-of ACTUAL_TIMESTAMP_WITH_OFFSET --output /private/new-readiness.json

python scripts/demand-candidate.py fit \
  --issues /private/issues.jsonl --prices /private/prices.jsonl \
  --protocol docs/forecasting/demand-candidate-protocol-2026-10-30.json \
  --as-of ACTUAL_TIMESTAMP_WITH_OFFSET --output /private/demand-experiment-v1

python scripts/demand-candidate.py evaluate \
  --issues /private/issues.jsonl --prices /private/prices.jsonl \
  --protocol docs/forecasting/demand-candidate-protocol-2026-10-30.json \
  --as-of ACTUAL_TIMESTAMP_WITH_OFFSET --experiment /private/demand-experiment-v1 \
  --frozen-model /private/models/solar-183d-20261026-local-v1 \
  --output /private/new-heldout-report
```

Readiness and output versions never overwrite prior evidence. Fit refuses incomplete training support, early fitting, or fitting once testing has begun. Evaluation refuses interim test peeking and verifies the original protocol, pre-test artifact timing and frozen model hash. It reports every comparator on identical complete pairs, including losing weeks. The same input policy is available through explicitly opted-in experimental HTTP inference; missing demand fails rather than silently using a missing-feature recipe.

## Decision and remaining work

Report MAE/RMSE overall, ordinary prices, spike prices, each horizon and both held-out weeks. Report rule-based precision/recall with their support limits, early warning at fixed leads, false target runs, and separately false issue half-hours/episodes/exposure. Preserve actual issue times; do not relabel a 15:03 forecast as issued at 15:00. Rule flags are not independently human-labelled events or delivered app notifications.

The price report intentionally states `savingsValidation=PENDING_REAL_F4_REPLAY`. Before adoption, pass the common predictions through the existing F4 replay with identical household profiles, feasible windows, minimum saving and plan-selection policy; report total savings, harmful reschedules, realised losses and losing weeks against frozen solar and B3. The code does not substitute a simplified appliance optimizer or claim bill savings. That outcome replay cannot be run meaningfully until the held-out predictions and actuals exist.

Stop after this candidate/control comparison. Do not launch another parameter search to make the result positive. Keep the current model if demand fails to improve the matched control and the current system's relevant price, warning and savings outcomes. Even a positive two-week result does not establish consistent performance across seasons. CSDT4-100 remains In Progress pending the actual demand-aware fit and evaluation.

## Verification

Contract tests cover available-versus-future demand, missing solar/target rejection, schema-3 stability, Java snapshot shape and evidenced mapping, first-issue deduplication, real actual-price receipt, split purge, truth revision cutoff, fit/test timing, immutable output, synthetic 24-model candidate/control fitting and loading, identical held-out pairs, demand-sensitive HTTP prediction and blocked approved-mode promotion. Synthetic outcomes are plumbing checks only.

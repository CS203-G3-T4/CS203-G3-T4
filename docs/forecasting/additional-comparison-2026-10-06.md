# Additional frozen-model comparison — 6 October 2026

**Experimental — limited training data.** These are already-seen diagnostics, not an additional independent test. No fitting, model selection change, production promotion or collector modification occurred.

## Is the model trained on all collected data?

No. The selected candidate was trained on eligible targets **September 24–October 2**, ending at October 3 midnight SGT. Direct models have **352 examples per horizon**, B1 correction models 342, and B2 correction models 304. Training excludes targets crossing the cutoff, unavailable truth/input windows and absent baseline anchors. Revisions are not counted as separate observations.

Later observations are held out for comparisons. Training on all collected observations and measuring error on those same observations would give in-sample error, not an honest accuracy test. Nothing in this comparison refits the frozen artifacts.

## Fresh data audit

- Snapshot acquired **October 6, 03:19:31 SGT**; **572 rows, 556 distinct price periods, 16 revisions, 0 parsing failures**.
- October 3: 48/48 prices; already-seen diagnostic.
- October 4: 10/48; excluded as an incomplete day.
- October 5: 48/48; repeat of the previous held-out check, not new independent evidence.
- October 6: 7/48 at snapshot; current incomplete day, excluded.
- The original 24-half-hour / 12-hour forecast contract is unchanged. Incomplete days were not converted into a shorter-horizon test.

Dataset SHA-256: `586a60c249be67694e407d85dddc29021d4589b0e8ff8e64d7b73ed5e43b20ce`.

## MAE on identical available-method pairs

October 3 uses **576 common pairs (24 origins)**. October 5 uses **456 common pairs (19 origins)** because B1 lacks history at the first five origins. All rows in this table use exactly the same forecast origin/target pairs and provisional truth within each column.

The pooled column weights **1,032 pairs**, 43 origins and 89 distinct target periods from both dates. The overlapping pairs are not independent observations. This pooled diagnostic does not change candidate selection.

| Method | October 3 MAE | October 5 MAE | Pooled diagnostic MAE |
| --- | ---: | ---: | ---: |
| HGB_SMALL | 199.08 | 48.71 | 132.64 |
| HGB_MAE | 179.47 | 58.49 | 126.01 |
| CURRENT | 356.87 | 125.36 | 254.58 |
| MEDIAN | 100.72 | 100.91 | 100.81 |
| PERSISTENCE | 203.34 | 148.54 | 179.13 |
| B1 | 203.14 | 176.61 | 191.42 |
| HGB_WEEKLY | 195.65 | 49.79 | 131.20 |
| HGB_RESIDUAL_B1 | 191.34 | 70.06 | 137.75 |
| RIDGE_RESIDUAL_B1 | 475.13 | 153.18 | 332.88 |

**Interpretation:** HGB_SMALL improves on the existing 15-leaf absolute-error HGB on October 5, but is worse on October 3. Pooled diagnostic error is **132.64 vs 126.01**, approximately **5.26% higher**. The training median wins this pooled comparison at **100.81**. The smaller HGB is therefore not demonstrated to be consistently more accurate. The original squared-error architecture is the `CURRENT` row; `HGB_MAE` is the 352-example absolute-error reference.

### B2/B3 coverage

B2 has **46.31 MAE on the 576 October 3 pairs**, better than both HGB variants and the median. On October 5 it has zero eligible pairs because the previous day is incomplete. B2 residuals are likewise unavailable that day; no pooled B2 score or AI-vs-B2 skill is claimed.

Including B3 on October 3 reduces the common set to **312 pairs (13 origins)**. These separate MAEs must not be compared with the 576-pair October 3 table:

| Method | MAE on the same 312 B3-eligible pairs |
| --- | ---: |
| B1 | 271.79 |
| B2 | 49.10 |
| B3 | 140.48 |
| HGB_MAE | 181.76 |
| HGB_SMALL | 190.54 |

B3 has no eligible pairs on October 5. Full candidate-set common pairs are 576 on October 3 and zero on October 5; available-method and seasonal subsets are reported separately rather than silently filling missing baselines.

## Saved checks and horizon results

- Reused the saved final model pipelines and shared as-of evaluator. `Pipeline.fit` was replaced with a failure guard during execution to prove this diagnostic cannot train.
- Checked model, selection and future-plan checksums before and after execution; all are unchanged.
- Recalculated each common-pair MAE from saved predictions and actuals; checked matching target/truth across methods, target-end split boundaries and provisional truth cutoff.
- Saved every prediction/exclusion, per-day metrics, pooled metrics and MAE/counts for every horizon. See [summary](artifacts.md#retrieval) and [horizon CSV](artifacts.md#retrieval).
- Existing 19 ML tests remain passed; there were no ML-code changes in this diagnostic turn.

Raw artifacts and the executed script:

```text
/home/bryan/CS203_T4/wattly-f3-research/additional-frozen-comparison-2026-10-06/run.py
/home/bryan/CS203_T4/wattly-f3-research/additional-frozen-comparison-2026-10-06/run.log
/home/bryan/CS203_T4/wattly-f3-research/additional-frozen-comparison-2026-10-06/results
```

## What remains unproven

No new complete untouched day has become available. The frozen **October 7–13** confirmation is still awaiting data and must not be scored until **October 14 midnight SGT**. Its exact commands are in [the frozen-study report](accuracy-study-2026-10-06.md#frozen-future-confirmation). Do not change the candidate based on these already-seen diagnostics.

One or two days cannot establish general accuracy, eight-week uncertainty or spike prediction quality. Configured spike reference history remains insufficient. Collector gaps and limited history are still material constraints.

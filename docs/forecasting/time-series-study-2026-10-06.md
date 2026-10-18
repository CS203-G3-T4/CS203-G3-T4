# Recursive time-series benchmark — 6 October 2026

**Experimental — limited training data.** A locally trained recursive AR(6) model was compared with the saved HGB benchmarks. This is not Chronos/TimesFM or a pretrained neural model. No independent accuracy, spike accuracy or production approval is claimed.

## Model and training

AR(6) learns a single linear equation for the next price using six consecutive half-hour prices (three hours of context). A StandardScaler and ridge regression are fitted only on training examples. Three fixed ridge penalties—1, 10 and 100—were compared using earlier chronological validation. The selected penalty is **100**; no test-day tuning occurred. No extra libraries or production dependencies were installed.

The equation is applied repeatedly: each predicted price becomes a lag for the next prediction. At a half-hour origin, the current interval price is normally unpublished, so AR predicts that interval internally before returning the next 24 future target starts. This alignment is explicitly tested: **24 returned half-hour intervals / 12-hour forecast**, with the same targets as HGB. Earlier actuals can be used only if publication and first availability are at/before the origin. No observed future price enters recursion, no targets are interpolated, and no row shifts cross gaps. Negative prices are retained.

Final AR training contains **414 distinct eligible one-step target periods** from September 24–October 2, ending October 3 midnight SGT. Of 432 possible target periods, six lack truth and 12 lack complete/fresh lag history. Training truth publication, availability and interval end must precede/equal the cutoff. Expanding folds contain **144, 192, 240, 288, 326, 366** one-step examples. The saved HGB has **352 examples per horizon** in its final fit; its 24 direct models require complete 12-hour target windows, so training sample counts differ. Both families use the same historical date range and causal availability rules. Revisions are not independent periods.

The benchmark HGB models were **loaded/reused**, not retrained for this comparison. The source snapshot is the existing read-only October 6 03:19:31 SGT snapshot (572 rows, 556 periods, 16 revisions). Dataset SHA-256: `586a60c249be67694e407d85dddc29021d4589b0e8ff8e64d7b73ed5e43b20ce`. Original reports and future confirmation plans were checked unchanged.

## MAE on identical eligible pairs

Every model uses the same origin/horizon/target/truth pairs within a column. MAE is SGD/MWh. Validation spans September 27–October 2. October 3 and October 5 were already seen previously, so their results are diagnostics after selection, not independent test evidence.

| Method | Validation, 2,938 pairs | October 3, 576 pairs | October 5, 456 pairs |
| --- | ---: | ---: | ---: |
| AR6_RIDGE_1 | 210.77 | 163.48 | 138.02 |
| AR6_RIDGE_10 | 210.67 | 163.65 | 138.23 |
| AR6_RIDGE_100 | 209.53 | 164.87 | 139.87 |
| HGB_MAE | 186.61 | 179.47 | 58.49 |
| HGB_SMALL | 184.19 | 199.08 | 48.71 |
| HGB_REGULARISED | 162.29 | 221.57 | 47.35 |
| CURRENT | 242.08 | 356.87 | 125.36 |
| MEDIAN | 163.84 | 100.72 | 100.91 |
| PERSISTENCE | 214.58 | 203.34 | 148.54 |
| B1 | 238.09 | 203.14 | 176.61 |
| B2 | 270.36 | 46.31 | Unavailable |

**AR(6) loses overall validation:** 209.53 versus existing HGB_MAE 186.61 (**12.28% more error**) and HGB_REGULARISED 162.29 (**29.11% more error**). It beats both HGB variants on only one of the six validation days, and beats the median on none. On the already-seen October 3 diagnostic it beats HGB_MAE, but on October 5 it is substantially worse. The regularised HGB also loses to HGB_MAE on October 3; its earlier validation win does not establish consistent day-to-day accuracy.

Validation has 258 distinct target periods and daily common-pair counts 576, 576, 576, 538, 312 and 360. Each candidate has 6,912 potential pairs: 3,456 are purged because the complete target window crosses midnight; 55 own pairs lack truth; 463 additional own-eligible pairs are excluded from the primary comparison because another method is unavailable. B3 has only 624 own-eligible validation pairs and is retained in records/exclusions but omitted from selection because it lacks coverage in every fold.

October 3 has 24 eligible origins; October 5 has 19. Each origin contributes 24 targets. October 5’s first five origins lack the required contiguous history after the October 4 outage, and the last 24 origins cross midnight. B2 is unavailable on October 5, making the full all-method common set zero; the 456-pair table explicitly uses the available-method subset. There is no pooled B2 score across these dates.

## Horizons, ordinary prices and high-price tails

| Method | Horizons 1–6 | Horizons 7–12 | Horizons 13–24 |
| --- | ---: | ---: | ---: |
| AR6_RIDGE_100 | 162.61 | 205.74 | 235.54 |
| HGB_MAE | 145.27 | 166.68 | 218.04 |
| HGB_REGULARISED | 131.98 | 143.99 | 187.23 |
| MEDIAN | 135.82 | 159.79 | 180.29 |

AR error increases from **162.61** at horizons 1–6 to **235.54** at horizons 13–24. This is consistent with the risk of feeding prediction errors into later predictions. Full per-horizon MAEs and true pair counts are in [the CSV](time-series-horizons-2026-10-06.csv).

On 2,841 ordinary pairs, AR MAE is **189.22**, worse than existing HGB **161.12**. On the 97 training-defined high-tail pairs (nine distinct targets), AR scores **804.20**, better than HGB **933.12** and regularised HGB **953.01**. These sparse high-tail results do not overcome the ordinary-period losses and do not establish spike prediction quality. Tail thresholds are each fold’s training-only 95th percentile, not configured spike labels.

Configured spike evaluation has **zero assessed pairs and zero labelled positive/negative targets**. Precision/recall remain null: **Insufficient history**. Several days of overlapping forecasts cannot support confidence intervals or consistent-accuracy claims. Demand and weather remain excluded because historical availability is unverified.

## Saved comparison view

Open [the standalone comparison](time-series-comparison-2026-10-06.html) in a browser. It works from a local file without a server, external scripts or the live app. Select any of the **43 eligible diagnostic origins** to compare actual prices, selected AR(6), existing HGB, regularised HGB, median and B1. The highlighted window shows one origin; the evaluation table covers all eligible origins. All 1,032 origin/target chart points match the saved diagnostic records. Default selection is the earliest eligible origin on the latest complete diagnostic date, not the origin with the smallest error.

The actual application frontend was not changed. This view does not serve a promoted model, override stale-data or actionable-recommendation guards, expose an admin route, or contact the collector. No current-model registry pointer was written.

## Reproduce and inspect

From the repository root, use a new output directory:

```bash
source /home/bryan/CS203_T4/wattly-f3-demo/runtime.env
source scripts/forecast-period-mapping.env
OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 MKL_NUM_THREADS=1 \
  "$WATTLY_DEMO_PYTHON" -m wattly_ml.cli time-series-study \
  /home/bryan/CS203_T4/wattly-f3-research/datasets/sqlite-9e7ff923d67d59634ed2 \
  --reference /home/bryan/CS203_T4/wattly-f3-research/2026-10-06-stability-v1 \
  --output /home/bryan/CS203_T4/wattly-f3-research/NEW_TIME_SERIES_RUN
```

All fitted candidates, sample timestamps, predictions, exclusions and diagnostics are saved at `/home/bryan/CS203_T4/wattly-f3-research/2026-10-06-time-series-v1`. Losing candidates are retained. Existing immutable HGB studies and the October 7–13 / October 14–20 confirmation plans are unchanged. No AR model was added to those previously frozen plans; an independent AR test would require its own later untouched window.

## Verification

- **Three new tests passed** in 9.08 seconds, with one existing Starlette/AnyIO deprecation warning. They cover recursive timestamp alignment, unpublished-interval bridging, negative prices, missing lags, future prices, late training revisions, training-only scaling, chronological selection before diagnostics, matching pairs, saved-HGB reuse without fitting, immutable output and changed-artifact rejection. Synthetic fixtures are used only for software checks.
- Independent verification reconstructs ridge coefficients using the closed-form equations, training scalers, **13,299 saved AR forecasts**, exact target alignment, provisional truth availability, and overall/daily/per-horizon MAEs. Every prior reference file checksum remains unchanged. See [audit](time-series-verification-2026-10-06.json).
- Standalone JavaScript syntax and DOM/data checks passed for all 43 origin selections: 24 points, six series, finite chart coordinates, accessible title and matching displayed MAE. This is structural verification, not a browser-layout or screenshot claim. See [view checks](time-series-view-verification-2026-10-06.json).
- `git diff --check` passed. No production/shared serving code changed; the existing full ML suite was not rerun in this experiment. Focused logs/XML are `wattly-f3-research/time-series-tests-final-2026-10-06.*`. The initial incomplete synthetic reference fixture failure is retained separately.

**Interpretation:** this simple trained time-series model is a useful comparison, but it does not beat the current HGB benchmark overall. It provides no evidence about an untested pretrained Chronos/TimesFM model. More verified historical data and a separate untouched evaluation are still needed before any promotion claim.

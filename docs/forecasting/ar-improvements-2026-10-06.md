# Time-series improvements — 6 October 2026

**Experimental — limited training data.** All four requested changes were implemented and trained offline. The selected candidate is **AR6_HUBER**. No production model was promoted, no application UI was changed in this experiment, and the collector was not modified or interrupted. Prior uncommitted work is preserved.

## Result compared with the first AR iteration

Six earlier chronological validation days, September 27–October 2, provide **2,938 identical eligible origin/target pairs** for every row below. MAE is SGD/MWh; smaller is better. The HGB and original AR predictions were reused from frozen studies, not refitted or substituted.

| Method | Validation MAE | Final training examples per estimator |
| --- | ---: | ---: |
| **AR6_HUBER — selected new time-series candidate** | **161.52** | 414 |
| HGB_REGULARISED — earlier HGB candidate | 162.29 | 352 |
| Training median | 163.84 | 352 |
| DIRECT6_HUBER | 178.15 | 342 |
| HGB_SMALL | 184.19 | 352 |
| HGB_MAE — existing benchmark | 186.61 | 352 |
| AR6_RIDGE_100 — first selected AR iteration | 209.53 | 414 |
| Persistence — latest known price | 214.58 | No fitted model |
| AR6_DELTA_RIDGE | 215.23 | 414 |
| DIRECT6_RIDGE | 226.70 | 342 |
| AR6_DAILY_DELTA_HUBER | 235.07 | 377 |
| B1 — mean of six preceding known prices | 238.09 | No fitted model |
| CURRENT — original squared-error HGB | 242.08 | 352 |
| DIRECT6_DAILY_DELTA_HUBER | 254.13 | 294 |
| B2 — same target interval yesterday | 270.36 | No fitted model |
| DIRECT6_DAILY_RIDGE | 330.94 | 294 |
| AR6_DAILY_RIDGE | 382.22 | 377 |

The Huber candidate reduces pooled validation error by **22.91% versus the first AR**, **13.44% versus HGB_MAE**, and only **0.47% versus HGB_REGULARISED**. These are reductions in MAE on reused development data, not accuracy percentages or future guarantees. It beats the first AR on all six validation days, HGB_MAE on four, the median on five, and HGB_REGULARISED on only one. The small pooled win over regularised HGB does **not** establish consistent superiority.

Every losing candidate, fitted pipeline, prediction and exclusion is retained. No settings were changed after seeing the diagnostic days. The complete results are in [JSON](ar-improvements-2026-10-06.json) and [per-horizon CSV](ar-improvements-horizons-2026-10-06.csv).

## How the four changes work

1. **Daily seasonal lag:** append the actual price at exactly `target start − 1 Singapore calendar day`, using only a revision published and available by the origin. Missing exact anchors exclude the complete window; there is no interpolation or imputation. The daily Ridge candidate loses badly here. Daily-feature training also has fewer usable examples, so this is not a controlled claim about the feature's causal effect alone.
2. **Robust Huber loss:** fit a linear AR equation to six exact recent prices. Huber uses squared loss for smaller residuals and linear loss for larger residuals, reducing large errors' influence during training while retaining every eligible observation. Fixed parameters are `alpha=0.01`, `epsilon=1.35`, `max_iter=1000`, `tol=1e-6`. Ridge keeps its previously selected `alpha=100`. This compares fixed estimator configurations; it does not isolate loss from regularisation. Huber improves ordinary-period forecasting but worsens high-price-tail error.
3. **Price changes:** replace the six price levels with their five adjacent differences. Fit `actual target price − latest origin-known price`, then add that known anchor back at inference. Recursive versions add each predicted change to the latest predicted price. These features/labels are invariant to a constant shift in all prices. The delta Ridge candidate does not improve over the original AR.
4. **Direct horizons:** fit 24 separate regressors, one for each future half-hour start. All 24 read the same origin-known recent prices and their own allowed daily anchor; no prediction feeds into another estimator. Direct Huber improves over first-iteration AR and HGB_MAE, but loses to recursive Huber. Direct Ridge also loses to first-iteration AR.

Two additional fixed combinations include daily seasonality, changes and Huber in recursive and direct form. They also lose. No new libraries, neural model, demand or weather features were added.

## Data, counts and leakage boundaries

Reused the frozen read-only collector snapshot acquired **October 6, 03:19:31 SGT**: **572 revision rows, 556 distinct half-hour periods, 16 revisions, zero parsing failures**. Revisions are alternate vintages of a period, not extra independent observations. Dataset SHA-256:

```text
586a60c249be67694e407d85dddc29021d4589b0e8ff8e64d7b73ed5e43b20ce
```

The existing verified zero-offset period mapping was applied through `scripts/forecast-period-mapping.env`. The mapping evidence and immutable source manifests are unchanged. Training begins September 24; each expanding fold stops at the start of its validation day. Final training stops at **October 3 00:00 SGT**. The final model does not learn from October 3–5 or their late revisions of earlier prices.

| Training variant | Examples/estimator in the six folds | Final examples/estimator | Estimators per fit |
| --- | --- | ---: | ---: |
| Recursive, recent levels or changes | 144, 192, 240, 288, 326, 366 | 414 | 1 |
| Recursive with daily lag | 111, 159, 207, 255, 293, 331 | 377 | 1 |
| Direct, recent levels | 120, 168, 216, 264, 301, 317 | 342 | 24 |
| Direct with daily lag | 83, 131, 179, 227, 264, 278 | 294 | 24 |
| Preserved direct HGB | 120, 168, 216, 264, 301, 322 | 352 | 24 |

Direct AR requires six exact contiguous recent prices; HGB's existing training-only missing-feature handling has a different eligibility rule. Thus final training counts differ even though evaluation pairs and historical date boundaries match. Multiplying 342 by 24 does not produce 8,208 independent price observations.

All input vintages must have both publication and availability at/before their origin. Training truth must have interval end, publication and availability at/before the training cutoff. Whole direct target windows crossing a cutoff are purged. No shuffle occurs. StandardScaler is fitted only on training vectors; no data is imputed. Delta anchors and daily references follow the same vintage rules. Demand/weather are excluded because their matching historical availability is unverified.

Validation contains **258 distinct targets**; its six daily common-pair counts are **576, 576, 576, 538, 312, 360**. Each method has 6,912 potential pairs. Nonseasonal candidates exclude 3,456 pairs at daily split boundaries and 55 for missing truth; another 463 own-eligible pairs fall outside the common set. Seasonal candidates exclude those 463 pairs themselves for insufficient history. Primary and separate per-candidate matched comparisons all happen to have the same **2,938 pairs** on this snapshot. The saved matched tables still expose each candidate's own coverage.

B3 has only **624 own-eligible validation pairs** and lacks coverage in every fold. It remains in saved records and exclusion reports but is not used for selection or assigned a misleading primary MAE.

## Horizons and high-price periods

The contract remains **24 half-hour predictions / a 12-hour forecast**. Recursive methods first bridge the unpublished current interval internally, then return the same 24 future target starts as direct models and HGB.

| Method | Horizons 1–6 | Horizons 7–12 | Horizons 13–24 |
| --- | ---: | ---: | ---: |
| AR6_HUBER | 140.81 | 163.37 | 171.19 |
| First AR6_RIDGE_100 | 162.61 | 205.74 | 235.54 |
| DIRECT6_HUBER | 145.41 | 175.51 | 196.31 |
| HGB_MAE | 145.27 | 166.68 | 218.04 |
| HGB_REGULARISED | 131.98 | 143.99 | 187.23 |
| Median | 135.82 | 159.79 | 180.29 |

Huber AR's largest relative gain over first-iteration AR is at longer horizons. It still loses to regularised HGB on the shorter horizon groups. The CSV reports all 24 horizons, every candidate, actual counts and diagnostic/matched comparison sets.

On **2,841 ordinary pairs**, Huber AR has **133.60 MAE**, versus first AR **189.22**, HGB_MAE **161.12**, and HGB_REGULARISED **135.29**. On the **97 high-tail pairs covering only nine distinct target periods**, it has **979.12 MAE**, worse than first AR **804.20**, HGB_MAE **933.12**, and HGB_REGULARISED **953.01**. Those 3.30% of pairs contribute 20.01% of Huber AR's absolute error. The tail boundary is each fold's training-only 95th percentile; it is not the configured spike definition.

Configured spike assessment still has **zero assessed pairs, zero labelled positive targets and zero labelled negative targets**. Precision/recall remain null: **Insufficient history**. Overlapping forecast errors are dependent; there are too few weeks for the existing confidence-interval requirement.

## Already-seen diagnostics, excluded from selection

| Method | October 3: 576 matched pairs | October 5: 456 matched pairs, nonseasonal subset |
| --- | ---: | ---: |
| Selected AR6_HUBER | 89.47 | 89.83 |
| First AR6_RIDGE_100 | 164.87 | 139.87 |
| DIRECT6_HUBER | 113.34 | 99.84 |
| HGB_MAE | 179.47 | 58.49 |
| HGB_REGULARISED | 221.57 | 47.35 |
| Median | 100.72 | 100.91 |
| Persistence | 203.34 | 148.54 |
| B1 | 203.14 | 176.61 |
| B2 | 46.31 | Unavailable |

October 3 has **24 eligible origins × 24 horizons = 576 pairs**; the last 24 origins' 576 pairs are purged because their complete target window crosses midnight. October 5 has **19 origins × 24 horizons = 456 pairs**: the first five origins lack complete recent history after the October 4 outage, and the last 24 origins cross midnight. Each comparison retains identical target prices for every included method.

The full-method intersection on October 5 is **zero** because B2 and the daily-lag variants lack the required previous-day history. Their MAE stays null. The October 5 table explicitly uses the separate nonseasonal available-method intersection. Both dates were already inspected in earlier studies; neither is a new untouched test. Huber AR improves on the first AR on both, but loses to HGB on October 5. This is diagnostic evidence only.

## Frozen confirmation and reproducible commands

All eight candidates, fold/final models, selection, vintage-safe predictions, exclusions and a separate frozen plan are saved at:

```text
/home/bryan/CS203_T4/wattly-f3-research/2026-10-06-ar-improvements-v2
```

The earlier AR/HGB studies and HGB confirmation plans remain unchanged. HGB_SMALL keeps October 7–13; HGB_REGULARISED keeps October 14–20. **AR6_HUBER confirmation is October 21–27 SGT**, evaluated after **October 28 00:00 SGT**. Confirmation never fits, reselects or promotes. It validates frozen artifact/selection/plan hashes and library versions and reports insufficient coverage explicitly. An early attempt correctly refused the October 6 dataset and created no output directory. Seven days are an initial check, not the required eight-week production evidence.

Reproduce the experiment from the repository root, choosing a new immutable output directory:

```bash
source /home/bryan/CS203_T4/wattly-f3-demo/runtime.env
source scripts/forecast-period-mapping.env
OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 MKL_NUM_THREADS=1 \
  nice -n 10 "$WATTLY_DEMO_PYTHON" -m wattly_ml.cli time-series-improvement-study \
  /home/bryan/CS203_T4/wattly-f3-research/datasets/sqlite-9e7ff923d67d59634ed2 \
  --reference /home/bryan/CS203_T4/wattly-f3-research/2026-10-06-time-series-v1 \
  --output /home/bryan/CS203_T4/wattly-f3-research/NEW_AR_IMPROVEMENT_RUN
```

After October 28 midnight SGT, acquire/prepare a **new read-only snapshot** using the existing [runbook](runbook.md) or [snapshot example](accuracy-study-2026-10-06.md), then score the frozen models with its prepared dataset:

```bash
OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 MKL_NUM_THREADS=1 \
  "$WATTLY_DEMO_PYTHON" -m wattly_ml.cli confirm-time-series \
  /absolute/path/to/new/prepared-dataset \
  --study /home/bryan/CS203_T4/wattly-f3-research/2026-10-06-ar-improvements-v2 \
  --output /home/bryan/CS203_T4/wattly-f3-research/confirmation-ar-2026-10-28
```

No new collection/retraining daemon or confirmation schedule was installed. Keep confirmation data untouched for model selection. More seasonal/spike history remains necessary: use the existing [historical-data acquisition/import plan](accuracy-study-2026-10-06.md#concrete-historical-data-acquisition-and-import) and [exact missing periods](historical-data-needed-2026-10-06.csv). Official final-price files with unknown publication vintages belong in a separate retrospective experiment, not a fabricated repair of operational availability.

## Verification and preserved unsuccessful work

The independent [audit](ar-improvements-verification-2026-10-06.json) reconstructs **700 fitted training matrices/scalers**, ridge coefficients from the closed-form equation, **31,788 saved forecast values** from explicit linear equations, and **6,288 per-horizon scores** across 19 comparisons. Forecast reconstruction difference is zero. It verifies target/truth identity, availability boundaries, common-pair MAEs, **57 unchanged reference files**, the later confirmation window and early rejection. Its 160,238 checked training labels include repeated horizons/folds; they are not independent observations.

All **350 Huber estimators** finish below the 1,000-iteration bound, with **zero ConvergenceWarnings**. The installed SciPy emits 350 `OptimizeWarning: Unknown solver options: iprint` warnings because this scikit-learn version passes a legacy output option; those warnings are retained separately as `fitWarnings`. No dependency was silently replaced and no warning was hidden. The preserved v1 report initially grouped these warnings under `convergenceWarnings`; v2 corrects their category. Predictions and scores are unchanged between v1/v2; no model choice or parameter was changed.

The initial synthetic fixture caused two Huber solver failures because its exact sawtooth labels yield a degenerate residual scale. That failed run/log remains saved. Fixed-seed noisy synthetic fixtures exercise the real solver without changing market data or fitting rules. Real solver failures are reported with candidate/estimator context, retained in an incomplete run, and never replaced silently.

**17 relevant regression tests passed** in 324.20 seconds. After the final warning-category correction, **8 focused tests passed again** in 51.12 seconds. These counts overlap. The focused tests cover daily anchors, revision/target-cutoff invariance, train-only scaling, delta shift invariance, direct/recursive alignment, missing history, solver failure, immutable output, frozen selection before diagnostics, and confirmation with fitting disabled. Regression includes existing AR, correction, retraining and stability tests. Each run has one existing Starlette/AnyIO deprecation warning. `git diff --check` passed. Exact commands, XML and logs are saved in [test evidence](ar-improvements-tests-2026-10-06.json).

**Accuracy remains unproven beyond these development/diagnostic dates.** Production promotion, UI serving changes, household comparisons, F4/F5 replay/authentication integration and collector changes are outside this experiment.

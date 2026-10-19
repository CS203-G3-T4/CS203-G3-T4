# Training-method experiments — 6 October 2026

**Experimental — limited training data.** These results use repeated development days for model selection. They are not a new independent test or production approval. The original experiments, confirmation plan and serving safeguards remain intact.

## What was implemented

- Reused the canonical dataset, as-of feature builder, purged trainer, statistical baselines and evaluator.
- Compared 14 fixed price/calendar candidates: existing squared-error HGB, absolute-error HGB, smaller HGB, training median, stronger regularisation, five-day training window, two shared-horizon models and six fixed blends.
- Blends assign 25%, 50% or 75% to HGB_SMALL and the remainder to the training median or origin-known B1. Missing baseline anchors remain unavailable.
- Shared models fit one estimator with an explicit 1–24 forecast-horizon feature. Their 8,448 pooled rows are dependent examples, not 8,448 observed prices.
- Stronger regularisation retains absolute-error loss, 40 iterations, seven leaves and seed 42; it increases minimum leaf samples from 20 to 40 and L2 from 1 to 10.
- Kept all candidate pipelines, losing runs, forecasts and exclusions outside serving. No UI, promotion, stale-data, actionable-recommendation, admin or collector changes.

## Data and chronological selection

Dataset SHA-256: `586a60c249be67694e407d85dddc29021d4589b0e8ff8e64d7b73ed5e43b20ce`. Reused the read-only snapshot acquired October 6 at 03:19:31 SGT: 572 revision rows, 556 distinct periods and 16 revisions; zero parsing failures. No revision is treated as another price period. Recorded period-mapping evidence is sourced from `scripts/forecast-period-mapping.env`; the original conservative snapshot manifest is unchanged.

Training begins September 24. Six expanding chronological folds validate September 27–October 2; the final training cutoff is October 3 midnight SGT. Every training target interval ends by its cutoff. Truth revisions must have been published and available by that cutoff, and feature revisions by each forecast origin. Late revisions and observations from October 3 onward cannot affect fitting or selection. Preprocessing is fitted only on each training set. Demand and weather remain excluded because matching historical availability is unverified.

Expanding fits have 120, 168, 216, 264, 301 and 322 examples per horizon during validation, and **352 per horizon** in the final fit. Final expanding training truth has **426 distinct periods**. The five-day model restricts training origins to September 28–October 2 and has **160 examples per horizon**; earlier origin-known history remains usable as lag context. Blends fit no extra estimator.

The primary comparison contains **2,938 identical origin/horizon/target pairs**, six origin days and **258 distinct target periods**. Daily pair counts are **576, 576, 576, 538, 312, 360**. There are 6,912 possible pairs per method: 3,456 are purged because the complete 12-hour target window crosses the day boundary; HGB has 55 missing-truth exclusions and 463 further own-eligible pairs excluded from the comparison because another method is unavailable. B2 has 480 insufficient-history and 38 missing-truth exclusions in addition to the purged pairs.

B3 has only 624 eligible pairs across this window and lacks coverage in every fold, so it is explicitly excluded from selection. It is retained in saved predictions/coverage; no score is compared against this primary table. No shorter forecast contract or missing-price imputation was used.

## MAE on the same 2,938 validation pairs

All MAEs are SGD/MWh. Daily wins are strict wins on each day’s identical eligible pairs.

| Method | Pooled MAE | Days beating HGB_MAE | Days beating median | Worst-day MAE |
| --- | ---: | ---: | ---: | ---: |
| HGB_REGULARISED | 162.29 | 6/6 | 4/6 | 259.25 |
| BLEND_SMALL_MEDIAN_25 | 163.06 | 5/6 | 3/6 | 224.43 |
| MEDIAN | 163.84 | 5/6 | 0/6 | 213.91 |
| BLEND_SMALL_MEDIAN_50 | 166.42 | 5/6 | 3/6 | 250.69 |
| BLEND_SMALL_MEDIAN_75 | 173.10 | 5/6 | 3/6 | 280.23 |
| HGB_SHARED_REGULARISED | 181.49 | 5/6 | 5/6 | 406.56 |
| HGB_SMALL | 184.19 | 3/6 | 2/6 | 314.43 |
| HGB_SHARED | 185.01 | 5/6 | 5/6 | 409.88 |
| HGB_MAE | 186.61 | 0/6 | 1/6 | 314.43 |
| BLEND_SMALL_B1_75 | 189.64 | 3/6 | 2/6 | 299.04 |
| HGB_RECENT5 | 190.59 | 2/6 | 1/6 | 314.43 |
| BLEND_SMALL_B1_50 | 202.47 | 2/6 | 0/6 | 296.35 |
| PERSISTENCE | 214.58 | 1/6 | 0/6 | 306.22 |
| BLEND_SMALL_B1_25 | 218.69 | 1/6 | 0/6 | 302.31 |
| B1 | 238.09 | 1/6 | 0/6 | 311.11 |
| CURRENT | 242.08 | 0/6 | 1/6 | 396.02 |
| B2 | 270.36 | 1/6 | 1/6 | 581.00 |

**Selection: HGB_REGULARISED.** Its validation MAE is **162.29**, 13.03% below HGB_MAE (186.61), 11.89% below HGB_SMALL (184.19), and only **0.95% below the median (163.84)**. It beats HGB_MAE and B1 on all six development days, and the median on four. Its worst day remains worse than the median’s worst day. Reused validation and candidate search can overstate improvement; these numbers do not establish consistent future accuracy.

The 25% HGB / 75% median blend also helps, but loses to the regularised candidate. Shared models win several ordinary days but fail more severely on the worst day. The shorter training window and B1 blends do not improve pooled error enough. No candidates were adjusted using October 3 or October 5 results; those already-seen diagnostics were neither used nor regenerated.

## Horizon errors and high-price periods

| Method | Horizons 1–6 | Horizons 7–12 | Horizons 13–24 |
| --- | ---: | ---: | ---: |
| HGB_REGULARISED | 131.98 | 143.99 | 187.23 |
| HGB_MAE | 145.27 | 166.68 | 218.04 |
| HGB_SMALL | 141.87 | 165.80 | 215.34 |
| MEDIAN | 135.82 | 159.79 | 180.29 |

The forecast remains **24 half-hour predictions / 12 hours**. Full horizon MAEs and actual pair counts for every method are in [the horizon CSV](artifacts.md#retrieval). Longer horizons remain harder.

HGB_REGULARISED ordinary-period MAE is **135.29** on 2,841 pairs, compared with HGB_MAE **161.12**. High-tail MAE is **953.01**, worse than HGB_MAE **933.12** and median **928.08**, on 97 pairs / nine distinct target periods. These tails contribute 19.39% of the new candidate’s absolute error from 3.30% of pairs. Thresholds use each fold’s training-only 95th percentile; they are not configured spike labels.

Configured spike evaluation has **zero assessed pairs, zero labelled positive targets and zero labelled negative targets**. Precision/recall remain null: **Insufficient history**. The existing long-history acquisition plan remains necessary; better regularisation does not resolve missing history or seasonal gaps.

## Separate frozen future confirmation

- Original HGB_SMALL confirmation remains **October 7–13**, evaluated after October 14 midnight SGT.
- New HGB_REGULARISED confirmation is **October 14–20**, evaluated after **October 21 midnight SGT**. Model settings and selection are already frozen; no fitting or reselection occurs in confirmation.
- `confirm-study` compares the frozen selection, existing HGB_MAE, HGB_SMALL, median, persistence, B1 and B2 on identical eligible pairs, and reports incomplete daily coverage explicitly.
- No future accuracy number exists yet. Seven days are an initial check, not eight-week accuracy evidence or production approval.

Artifacts: `/home/bryan/CS203_T4/wattly-f3-research/2026-10-06-stability-v1`. Model SHA-256: `6c8cabdad394363d27bb23434339f06931a61af54235e56634922db777e675cf`. Selection SHA-256: `04a4d63c08155f09499a84bc6fcb0ad762dad056e0b3782081f1af8b6462141f`.

From the repository root, reproduce the study with a **new output directory**:

```bash
source /home/bryan/CS203_T4/wattly-f3-demo/runtime.env
source scripts/forecast-period-mapping.env
"$WATTLY_DEMO_PYTHON" -m wattly_ml.cli stability-study \
  /home/bryan/CS203_T4/wattly-f3-research/datasets/sqlite-9e7ff923d67d59634ed2 \
  --previous-study /home/bryan/CS203_T4/wattly-f3-research/2026-10-06-corrections-v2 \
  --output /home/bryan/CS203_T4/wattly-f3-research/NEW_STABILITY_RUN
```

After October 21 midnight SGT, obtain a read-only backup including WAL and evaluate the frozen model:

```bash
source /home/bryan/CS203_T4/wattly-f3-demo/runtime.env
source scripts/forecast-period-mapping.env
stability_snapshot=$("$WATTLY_DEMO_PYTHON" -m wattly_ml.cli snapshot \
  /home/bryan/cs203/data/collector.sqlite3 --kind sqlite \
  --output /home/bryan/CS203_T4/wattly-f3-research/snapshots)
stability_dataset="/home/bryan/CS203_T4/wattly-f3-research/datasets/${stability_snapshot##*/}"
if [ ! -f "$stability_dataset/manifest.json" ]; then
  "$WATTLY_DEMO_PYTHON" -m wattly_ml.cli prepare "$stability_snapshot" --output "$stability_dataset"
fi
"$WATTLY_DEMO_PYTHON" -m wattly_ml.cli confirm-study "$stability_dataset" \
  --study /home/bryan/CS203_T4/wattly-f3-research/2026-10-06-stability-v1 \
  --output /home/bryan/CS203_T4/wattly-f3-research/stability-confirmation-2026-10-21
```

Keep the collector online. No automatic job was installed, and no collector files were written or service interrupted. If a confirmation day is incomplete, the report must retain its insufficient-coverage status. Further tuning needs a new later untouched window.

## Verification

Independent saved-prediction recalculation passed for overall, daily and per-horizon MAE; exact origin/target/truth identity, publication/availability cutoffs, exclusions, horizon counts, reference predictions, unchanged previous file checksums and frozen artifact hashes. Current-snapshot confirmation correctly refuses early evaluation and writes no report. See [audit](artifacts.md#retrieval).

**23 full ML regression tests passed**, plus **one separately run calendar-boundary check passed** (added after suite collection). The final full suite completed in 313.74 seconds, with one existing Starlette/AnyIO deprecation warning. It covers serving/fallback and promotion guards, training-only preprocessing, shared-estimator reuse, missing/negative blend inputs, identical pairs, purged targets, held-out-price and late-revision counterfactuals, immutable output, early-confirmation rejection and frozen confirmation with `Pipeline.fit` disabled. Test fixtures use synthetic data only, never reported as real forecast accuracy.

Logs and JUnit XML: `/home/bryan/CS203_T4/wattly-f3-research/stability-full-tests-v2.*` and `stability-calendar-test.*`. `git diff --check` passed. The initial non-midnight synthetic fixture failure was fixed; two preliminary runs with timeout stack dumps exited with native crashes. Their logs are retained. The final suite uses bounded native CPU threads and no timeout stack-dump option; it exited successfully. No model settings were changed in response to these software-test results.

Run the current complete suite (now including the extra calendar check):

```bash
OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 MKL_NUM_THREADS=1 \
  /home/bryan/CS203_T4/wattly-f3-demo/tools/venv/bin/python -m pytest ml/tests -q
```

For permitted historical acquisition/import commands and exact missing periods, see [the historical-data plan](accuracy-study-2026-10-06.md#concrete-historical-data-acquisition-and-import). The available dataset remains too short for production history requirements, reliable spike assessment, eight-week uncertainty, or proof that AI will consistently beat baselines.

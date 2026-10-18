# F3 collector retraining — snapshot 5 October 2026

**Experimental — limited training data.** No production model was promoted.

## Result

The retrained HGB did **not** improve on the frozen previous HGB on the identical October 3 holdout: **179.47 vs 176.40 SGD/MWh MAE**, a **1.74% increase in error**. It beats B1 by 11.66%, but loses strongly to B2 and the training median. The squared-error original architecture also worsened with retraining. Preserve these unsuccessful candidates; do not substitute a test-day winner for the validation-selected model.

The training median wins pooled validation; **HGB_MAE remains the best feature-based candidate**. Settings and model selection were frozen before evaluating October 3. The prior September 28 diagnostic (224.41 for HGB, 273.55 for original AI) is a different day and cannot establish improvement by comparison with these new scores.

## Read-only data audit

- Snapshot acquired **5 October 2026, 23:33:28 SGT** using the existing SQLite backup, including WAL. No collector code, process, service, environment, database rows or raw files were changed.
- **564 rows, 548 distinct half-hour periods, 16 revisions, 0 exact duplicates, 0 parsing failures.** Revisions are selected as of publication/availability, not treated as independent observations.
- Two isolated old observations on August 30 and September 4 were retrieved much later. Their original timestamps are preserved; neither can extend early training history.
- Across the full range there are **1,180 missing half-hours**; most precede September 23. Six half-hours are missing in the September 24–October 2 training interval (four on September 30, two on October 1).
- October 4 contains **10/48** periods, with a 38-period gap. October 5 contains **47/48** and was the current, uncompleted calendar day at snapshot time. **October 3 is the latest complete elapsed day with 48 labels available by its midnight cutoff.**
- Publication and retrieval timestamps exist on every revision; no availability precedes publication. Two isolated observations exceed 40 minutes of receipt lag; maximum lag is 2,651,532 seconds.
- Reproduced the existing mapping check against the snapshot: **24/24** archived September 29 USEP and rounded-demand matches; both adjacent-period alternatives have zero matches. The existing `EMC_RT_20260929_24_PERIODS` configuration applies to the zero-offset floor rule. This does not verify every later provider revision. Frozen manifests retain their conservative original mapping flags.

| Singapore date | Distinct periods |
| --- | ---: |
| 2026-08-30 | 1 |
| 2026-09-04 | 1 |
| 2026-09-23 | 15 |
| 2026-09-24 | 48 |
| 2026-09-25 | 48 |
| 2026-09-26 | 48 |
| 2026-09-27 | 48 |
| 2026-09-28 | 48 |
| 2026-09-29 | 48 |
| 2026-09-30 | 44 |
| 2026-10-01 | 46 |
| 2026-10-02 | 48 |
| 2026-10-03 | 48 |
| 2026-10-04 | 10 |
| 2026-10-05 | 47 |

Snapshot SHA-256: `dae12c1ddb26cb01a70cbedbbc0c97ecce3b91201c1e26703d23c268b4ceb618`.
Canonical dataset SHA-256: `2f640ffd3662c093b057fe2a0e9a1d18148a49899757e0b164549a2c89a3320a`.

## Training and leakage controls

- Reused the existing price/calendar features, exact timestamp lags, as-of revision selection, training example builder, statistical baselines and evaluator. Demand/weather remain excluded.
- Fixed candidates: original squared-error HGB; HGB with absolute-error loss; training median; ridge with progressively added lags, daily lags, rolling statistics, changes and calendar features. HGB settings remain 40 iterations, 15 leaves, L2=1, seed=42; ridge alpha=10. No new search or test-day tuning.
- Seven expanding chronological folds validate **September 26–October 2**. Every training target interval ends by its training cutoff. Every evaluated target ends by its split end. Preprocessing fits on training only. Future publication/availability revisions cannot enter earlier inputs or labels.
- Development rows have period, publication and availability strictly before **October 3 midnight SGT**. The old September 28 test is now permitted development history for the new holdout; its original report remains unchanged.
- After saving `selection.json`, refit the fixed candidates on **September 24–October 2**: **352 training origins per horizon**, 426 distinct training price periods, 80 excluded training origins out of 432. Of these 80, 24 cross the final split boundary and 56 fail the complete-target/fresh-input requirements. Compared with the frozen candidate, examples per horizon increased from **120 to 352**.
- Research-only models are saved separately with `productionEligible=false`. No serving registry pointer is created. This is historical as-of reconstruction, not proof that the new artifact existed or was served on October 3.

| Validation day SGT | Training examples per horizon | Common validation pairs |
| --- | ---: | ---: |
| 2026-09-26 | 72 | 576 |
| 2026-09-27 | 120 | 576 |
| 2026-09-28 | 168 | 576 |
| 2026-09-29 | 216 | 576 |
| 2026-09-30 | 264 | 538 |
| 2026-10-01 | 301 | 312 |
| 2026-10-02 | 322 | 360 |

## Identical-pair MAE comparison

All primary validation scores use **3,514 common pairs**, 148 origins and 305 distinct targets. Missing prices produce unequal horizon counts: 148 pairs for horizons 1–13, 147/146/145 for horizons 14/15/16, and 144 for horizons 17–24. The CSV now counts these explicitly instead of dividing the total by 24.

All primary held-out scores use **576 common pairs = 24 origins × 24 horizons**, covering **47 distinct actual target periods** on October 3. Each method excludes another 576 pairs from the 24 later origins because their complete 12-hour window would cross midnight. Repeated targets across overlapping windows are not independent observations.

| Method | Pooled earlier validation MAE | October 3 holdout MAE |
| --- | ---: | ---: |
| MEDIAN | 212.02 | 100.72 |
| HGB_MAE | 225.74 | 179.47 |
| CURRENT | 270.34 | 356.87 |
| PERSISTENCE | 275.10 | 203.34 |
| B1 | 288.64 | 203.14 |
| RIDGE_LAGS | 288.70 | 185.99 |
| B2 | 292.18 | 46.31 |
| RIDGE_DAILY | 449.36 | 202.51 |
| RIDGE_CALENDAR | 543.19 | 475.17 |
| RIDGE_CHANGES | 592.22 | 230.82 |
| RIDGE_ROLLING | 592.87 | 230.73 |
| FROZEN_HGB_MAE (previous 120-example fit) | Not used in new selection | 176.40 |
| FROZEN_CURRENT (previous 120-example fit) | Not used in new selection | 325.15 |

**B3 is a separate comparison**, because it lacks eligible pairs in the early validation folds. It now has 312 eligible held-out pairs (13 origins); another 264 are excluded for missing seasonal history, plus 576 at the split boundary. Do not compare the following MAEs with the 576-pair table above.

| Method | MAE on the same 312 B3-eligible pairs |
| --- | ---: |
| HGB_MAE | 181.76 |
| FROZEN_HGB_MAE | 159.70 |
| B1 | 271.79 |
| B2 | 49.10 |
| B3 | 140.48 |

## Error by forecast horizon

Hours denote target start relative to the half-hour forecast origin. Each held-out horizon below has 24 common pairs. Full candidate and validation results are in [the horizon CSV](artifacts.md#retrieval).

| Horizon | Hours | Retrained HGB | Frozen HGB | B1 | B2 | Training median |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 0.5 | 78.70 | 185.40 | 160.40 | 88.10 | 122.27 |
| 2 | 1 | 137.95 | 144.99 | 159.67 | 63.26 | 103.66 |
| 3 | 1.5 | 165.73 | 125.82 | 161.04 | 51.31 | 91.07 |
| 4 | 2 | 188.54 | 136.82 | 162.40 | 50.43 | 88.83 |
| 5 | 2.5 | 178.31 | 152.92 | 168.82 | 49.93 | 92.65 |
| 6 | 3 | 208.00 | 119.59 | 171.85 | 49.23 | 95.39 |
| 7 | 3.5 | 184.65 | 122.36 | 175.14 | 49.50 | 100.24 |
| 8 | 4 | 175.02 | 100.60 | 179.52 | 35.05 | 104.61 |
| 9 | 4.5 | 176.41 | 72.70 | 186.26 | 35.68 | 108.56 |
| 10 | 5 | 174.59 | 120.92 | 192.77 | 37.31 | 112.31 |
| 11 | 5.5 | 206.11 | 112.94 | 201.52 | 42.46 | 114.78 |
| 12 | 6 | 218.08 | 110.31 | 207.71 | 40.43 | 110.62 |
| 13 | 6.5 | 216.95 | 178.72 | 215.62 | 45.65 | 111.51 |
| 14 | 7 | 229.02 | 169.00 | 222.77 | 45.47 | 107.91 |
| 15 | 7.5 | 221.51 | 236.63 | 234.44 | 45.19 | 111.41 |
| 16 | 8 | 181.16 | 221.95 | 240.03 | 44.92 | 99.31 |
| 17 | 8.5 | 221.64 | 172.62 | 244.14 | 43.20 | 96.10 |
| 18 | 9 | 153.58 | 208.40 | 242.23 | 40.26 | 94.80 |
| 19 | 9.5 | 103.02 | 228.69 | 239.12 | 42.87 | 95.01 |
| 20 | 10 | 137.80 | 188.96 | 234.36 | 42.85 | 95.07 |
| 21 | 10.5 | 156.19 | 242.19 | 228.54 | 42.85 | 93.40 |
| 22 | 11 | 200.98 | 239.91 | 222.43 | 42.64 | 91.46 |
| 23 | 11.5 | 156.85 | 298.05 | 215.66 | 41.77 | 88.85 |
| 24 | 12 | 236.42 | 343.06 | 209.00 | 41.07 | 87.55 |

### Ordinary prices, high-price tail and spikes

- Earlier validation: HGB MAE **148.27** on 3,159 non-tail pairs and **915.11** on 355 high-tail pairs. The high-price tail contributes **40.95%** of absolute error from **10.10%** of pairs. The cutoff is the training-only 95th percentile for each fit; it is **not** the configured spike label.
- Earlier validation HGB horizon groups 1–6 / 7–12 / 13–24: **173.58 / 192.24 / 269.50 MAE**. Longer horizons contribute more error.
- October 3: all 576 test pairs are below the final training-defined high-tail cutoff. HGB group MAEs are **159.54 / 189.14 / 184.59**. The held-out day does not test high-price-tail performance.
- Configured spike references require at least 14 matching historical days. There are **0 assessed held-out pairs, 0 labelled positive targets and 0 labelled negative targets**. Precision and recall remain null: **Insufficient history**.
- Nine days of training and seven validation days cannot establish general AI accuracy, stable seasonal relationships or eight-week uncertainty. More data did not automatically improve this fit.

## Saved artifacts and reproduction

Raw snapshots, canonical rows, fitted models, full predictions and verification logs stay outside Git:

```text
/home/bryan/CS203_T4/wattly-f3-research/2026-10-05-retraining-v2
/home/bryan/CS203_T4/wattly-f3-research/retraining-verification-2026-10-05.json
```

The first run `2026-10-05-retraining-v1` is retained. Its MAEs and predictions match v2; its CSV horizon counts assumed complete origins and are superseded by v2. Neither run overwrote the earlier September experiment.

From the repository root, choose a **new output directory**:

```sh
source /home/bryan/CS203_T4/wattly-f3-demo/runtime.env
source scripts/forecast-period-mapping.env
"$WATTLY_DEMO_PYTHON" -m wattly_ml.cli retrain-study \
  /home/bryan/CS203_T4/wattly-f3-research/datasets/sqlite-dae12c1ddb26cb01a70c \
  --previous-study /home/bryan/CS203_T4/wattly-f3-research/2026-09-29-price-models-v1 \
  --output /home/bryan/CS203_T4/wattly-f3-research/NEW_RETRAINING_RUN
```

Independent verification recalculated every primary and B3-subset MAE, checked target/truth agreement across methods, reconstructed provisional truth by publication/receipt cutoff, checked split boundaries, recalculated each CSV horizon count and confirmed selection/reference hashes are unchanged. See [verification summary](artifacts.md#retrieval).

**Checks:** all **17 ML tests passed**, with one existing dependency deprecation warning. The new counterfactual test changes held-out prices and injects a late revision of a training period, then confirms every training matrix, target vector, validation score and selection rank is unchanged. The horizon-count regression test also passed after the final helper cleanup. The full test run required local socket access for the existing FastAPI checks; the restricted run was stopped after it stalled. Logs and JUnit XML are saved as `wattly-f3-research/retraining-tests-verified-2026-10-05.*` outside Git. `git diff --check` passed.

## Next data and evaluation

1. Check collector uptime and fill gaps only with genuine permitted historical observations. Final-price CSVs can supply retrospective labels, but cannot recreate missing live publication/receipt vintages. Keep those experiments separate.
2. Continue collecting complete calendar days and retain revisions with their real receipt times. Never duplicate revisions as additional training periods. The existing [historical acquisition plan](accuracy-study-2026-09-29.md#historical-importer-audit-and-concrete-acquisition-plan) still specifies required USEP columns and historical vintages.
3. Freeze any next candidate/settings before evaluating a new untouched period. Avoid repeatedly choosing settings on October 3. Use several complete days for confirmation when available.
4. Production approval remains blocked by existing long-history and validation requirements: 365 training days, 28 validation days, 28 warm-up days, verified/gap-free data; final accuracy evidence requires eight untouched weeks. No F4/F5, authentication, UI or recommendation safeguards were changed by this study.

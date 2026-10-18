# F3 baseline corrections and smaller-tree study — 6 October 2026

**Experimental — limited training data.** The selected learned candidate is **HGB_SMALL**. No production model or UI was changed.

## Measured result

On the same **456 eligible October 5 pairs**, the smaller-tree HGB has **48.71 MAE**, versus **58.49** for the existing HGB: **16.72% lower error on this one day**. Earlier validation selected the smaller model before these holdout outcomes were evaluated. This is a historical as-of reconstruction, not proof of live AI performance. The day has no training-defined high-price-tail cases, so spike robustness remains unproven.

The **training median wins overall validation**. It loses to the selected smaller HGB on October 5, but remains included in the frozen future comparison. Residual models were unsuccessful; their fitted models, predictions and errors are retained. No parameter was changed after inspecting October 5.

## Data and acquisition completed

- Read-only SQLite backup acquired **6 October 2026 at 01:15:13 SGT**, including WAL. **568 rows, 552 distinct half-hour periods, 16 revisions, 0 parsing failures**, actual publication and receipt timestamps preserved. No running collector changes.
- October 5 is now complete: 48 periods. October 4 still has only 10/48. The collector sidecar CSVs have **zero additional periods** for September 30, October 1 or October 4; they cannot repair those gaps.
- There are **44 missing periods in September 24–October 5**: 4 on September 30, 2 on October 1 and 38 on October 4. Exact official Date/Period identifiers are in [the acquisition CSV](artifacts.md#retrieval). No replacement prices or receipt timestamps were invented.
- Existing official cached history is still only September 20 (48 final prices, unknown historical publication/availability). It cannot extend the operational training series. The previously denied broader history download returned HTTP 403; access controls were not bypassed.
- Existing zero-offset floor-mapping configuration is reused. The 24-period September 29 verification was reproduced in the preceding retraining audit; its limited provider/date scope and immutable manifest flags remain intact.

Dataset SHA-256: `5f6c9380540fc0875297c5119b9a1977af94bf0ffff46ab4d1bb13a36271675f`.

## Fixed candidates and leakage controls

- Retained reference **CURRENT**, **HGB_MAE**, **MEDIAN**, persistence and B1–B3.
- **HGB_SMALL:** absolute-error HGB with **7 leaves**, versus the reference's 15. Both retain 40 iterations, L2=1 and seed=42, and the same price/calendar features.
- **HGB_WEEKLY:** the smaller HGB plus the existing same-target-last-week feature. That feature is absent in 100% of the first four fold training matrices and mostly absent in the last two. Training-only imputation is used; no additional weekly observations are invented.
- **HGB_RESIDUAL_B1/B2:** smaller HGB learns actual minus the corresponding origin-known baseline. At prediction time the correction is added to that baseline.
- **RIDGE_RESIDUAL_B1/B2:** the same residual target with ridge alpha=10; imputation and scaling fit training data only.
- Missing residual baseline anchors exclude the entire origin. They are never imputed or replaced with another baseline. Inputs and baseline anchors use actual publication and first availability at each origin.
- **October 3 remains completely outside fitting and model selection.** All period, publication and availability timestamps supplied to fitting/validation precede that midnight. Final refitting also stops there; it does not incorporate October 3, 4 or 5.
- Six expanding daily validation folds cover **September 27–October 2**. The initial training span is three days because B2 residuals have only 35 examples after two days, below the existing 48-example floor.
- Training targets cannot cross their cutoff; evaluated target intervals cannot cross their daily split end. There is no random shuffle, test-day search or per-horizon winner selection.

| Validation day SGT | Direct-model examples/horizon | B1 residual examples/horizon | B2 residual examples/horizon | Common pairs |
| --- | ---: | ---: | ---: | ---: |
| 2026-09-27 | 120 | 120 | 83 | 576 |
| 2026-09-28 | 168 | 168 | 131 | 576 |
| 2026-09-29 | 216 | 216 | 179 | 576 |
| 2026-09-30 | 264 | 264 | 227 | 538 |
| 2026-10-01 | 301 | 301 | 264 | 312 |
| 2026-10-02 | 322 | 317 | 283 | 360 |

**Final examples per horizon:** 352 for direct models and the median, 342 for B1 corrections, 304 for B2 corrections. Different residual training counts reflect genuine baseline availability; observations/revisions are not duplicated.

## Validation MAE on identical pairs

All rows below use **2,938 common earlier-validation pairs**. These six days differ from the previous seven-day study, so compare candidates within this table, not with that study's pooled MAE.

| Method | MAE, SGD/MWh |
| --- | ---: |
| MEDIAN | 163.84 |
| HGB_SMALL | 184.19 |
| HGB_WEEKLY | 184.19 |
| HGB_MAE | 186.61 |
| HGB_RESIDUAL_B1 | 209.06 |
| PERSISTENCE | 214.58 |
| B1 | 238.09 |
| CURRENT | 242.08 |
| B2 | 270.36 |
| HGB_RESIDUAL_B2 | 281.28 |
| RIDGE_RESIDUAL_B1 | 505.25 |
| RIDGE_RESIDUAL_B2 | 526.76 |

## October 5: common-pair coverage and exclusions

The full comparison set including B2 has **zero common pairs**, because the October 4 outage makes B2 and its residual models unavailable. B3 also lacks complete seasonal history. No all-method MAE or AI-vs-B2 improvement is claimed.

The separate available-method comparison has **19 origins × 24 horizons = 456 common pairs**, covering **42 distinct target periods**. Each horizon has 19 paired errors. It excludes the first five origins (120 pairs) because B1 lacks its complete six-period history, and the last 24 origins (576 pairs) because their full 12-hour windows cross midnight.

| Method | MAE on the same 456 pairs |
| --- | ---: |
| HGB_SMALL | 48.71 |
| HGB_MAE | 58.49 |
| FROZEN_HGB_MAE | 58.49 |
| MEDIAN | 100.91 |
| PERSISTENCE | 148.54 |
| B1 | 176.61 |
| HGB_WEEKLY | 49.79 |
| HGB_RESIDUAL_B1 | 70.06 |
| RIDGE_RESIDUAL_B1 | 153.18 |

B2, B3, HGB_RESIDUAL_B2 and RIDGE_RESIDUAL_B2 each exclude **576 early pairs for insufficient history**, plus **576 at the split boundary**. Their holdout MAEs remain null. The frozen previous 352-example HGB and newly refitted reference HGB predictions match exactly.

### Horizon errors and limitations

Full horizon MAEs and true pair counts are in [the CSV](artifacts.md#retrieval). Selected HGB validation group MAEs (1–6 / 7–12 / 13–24) are **141.87 / 165.80 / 215.34**. Longer horizons remain harder.

Validation high-tail MAE is **932.11** on 97 pairs, versus **158.65** on 2,841 non-tail pairs. The tail contributes **16.71%** of error from **3.30%** of pairs. This training-only 95th-percentile grouping is not a spike label.

October 5 contains **zero high-tail pairs**. Configured spike references still have **0 assessed pairs, 0 labelled positives and 0 labelled negatives**. Spike precision/recall are null: **Insufficient history**. Overlapping predictions are not independent observations; one held-out day cannot establish reliable general accuracy.

## Frozen future confirmation

Fitted models, their checksums, model selection, dependency versions and confirmation plan are saved separately:

```text
/home/bryan/CS203_T4/wattly-f3-research/2026-10-06-corrections-v2
/home/bryan/CS203_T4/wattly-f3-research/2026-10-06-corrections-v2/prospective.json
```

- Frozen at **6 October 2026, 01:26:26 SGT**.
- Confirmation: **October 7–13 SGT**, end exclusive **October 14 00:00 SGT**.
- `confirm-study` never fits or promotes. It verifies model, selection and plan hashes and the original dependency versions, then compares the frozen learned candidate, existing HGB, training median, persistence, B1 and B2 on identical eligible pairs.
- Evaluation uses actual as-of inputs and split-end provisional truth; reports excluded cases, daily completeness and real pair counts. Incomplete data gives `INSUFFICIENT_CONFIRMATION_COVERAGE`.
- The current snapshot was tested: evaluation correctly returns **Future confirmation pending**, writing no results. No future accuracy number exists yet.
- These seven days are an initial confirmation, not the existing eight-week final-accuracy requirement. Production approval stays false.

After October 14 midnight SGT, run from the repository root:

```sh
source /home/bryan/CS203_T4/wattly-f3-demo/runtime.env
source scripts/forecast-period-mapping.env
confirmation_snapshot=$("$WATTLY_DEMO_PYTHON" -m wattly_ml.cli snapshot \
  /home/bryan/cs203/data/collector.sqlite3 --kind sqlite \
  --output /home/bryan/CS203_T4/wattly-f3-research/snapshots)
confirmation_dataset="/home/bryan/CS203_T4/wattly-f3-research/datasets/${confirmation_snapshot##*/}"
if [ ! -f "$confirmation_dataset/manifest.json" ]; then
  "$WATTLY_DEMO_PYTHON" -m wattly_ml.cli prepare "$confirmation_snapshot" --output "$confirmation_dataset"
fi
"$WATTLY_DEMO_PYTHON" -m wattly_ml.cli confirm-study "$confirmation_dataset" \
  --study /home/bryan/CS203_T4/wattly-f3-research/2026-10-06-corrections-v2 \
  --output /home/bryan/CS203_T4/wattly-f3-research/confirmation-2026-10-14
```

Use a new output directory for a later rerun. Keep the collector laptop awake and online; the latest-only feed cannot recover missed past updates. No new cron job or collector modification was installed.

## Concrete historical-data acquisition and import

1. Obtain permitted **Uniform Singapore Energy Price (USEP), SGD/MWh**, files from the official EMC [Market Data Download](https://www.nems.emcsg.com/nems-prices#market-data-download). Do not substitute demand, LCP, RUSEP or a retail tariff.
2. Initial request: **August 8–October 2, 2026**, 56 days / 2,688 unique periods. Split into at most 31 days per file: August 8–September 7 and September 8–October 2. Keep October 3 and October 5 out of further model selection.
3. Separately request the 44 missing Date/Period rows in the acquisition CSV. Genuine old publication/retrieval vintages are required to repair operational history. Final-price-only files must remain a separate retrospective experiment.
4. Existing long-history approval floor would need **August 8, 2025–October 2, 2026**, 421 days / 20,208 unique periods: 28 warm-up, 365 training and 28 validation days. This does not guarantee approval or replace final untouched evaluation.
5. Preserve original files, provider provenance, settlement status, original publication and first-availability timestamps, and revision identity. Never infer receipt times from interval ends or count revisions as new periods.

For permitted downloaded final-price CSVs, reuse the existing immutable importer:

```sh
source /home/bryan/CS203_T4/wattly-f3-demo/runtime.env
history_snapshot=$("$WATTLY_DEMO_PYTHON" -m wattly_ml.cli snapshot /path/to/permitted-usep.csv \
  --kind emc --source-url https://www.nems.emcsg.com/nems-prices \
  --output /home/bryan/CS203_T4/wattly-f3-research/snapshots)
"$WATTLY_DEMO_PYTHON" -m wattly_ml.cli prepare "$history_snapshot" \
  --output /home/bryan/CS203_T4/wattly-f3-research/datasets/NEW_FINAL_HISTORY_DATASET
```

The existing `--retrospective --truth-policy FINAL --exploratory` training path can use such final-price files as a separately labelled study when sufficient rows are supplied. The correction-study commands intentionally reject unknown operational vintages. Historical acquisition remains blocked on a permitted multi-day export; no additional observations were fabricated.

## Verification and preservation

Independent recalculation confirms every validation and available-pair MAE, each horizon count, exact target/truth agreement, publication/availability cutoffs, split boundaries, unchanged reference hashes and frozen model/plan hashes. See [verification](artifacts.md#retrieval).

**19 ML tests passed**, with one existing dependency deprecation warning. The new checks cover origin-known residual labels, missing baseline anchors, immutable outputs, held-out/late-revision counterfactuals with unchanged fit matrices and selection, refusal of early confirmation, frozen-model evaluation without fitting, and rejection of changed selection artifacts. Synthetic confirmation data is used only for these software checks, never reported as real accuracy evidence. Full logs and JUnit XML are in `wattly-f3-research/correction-full-tests.*`; focused checks are in `correction-focused-tests.*`. `git diff --check` passed.

The original September experiment and October 5 retraining report remain unchanged. All losing candidates are retained. The preliminary correction run v1 is retained; v2 adds the protected confirmation-plan hash and includes median/persistence in holdout comparisons without changing fitted models or selection. Use v2 for confirmation.

No live collector, production registry, admin access, stale-data guard, actionable recommendation, F4/F5 integration or frontend behavior was changed by these experiments.

# F3 measured accuracy study — 29 September 2026

**Experimental — limited training data. No model promoted.**

## Result and decision

Chronological validation selected **MEDIAN**, a separate training-target median
for each of the 24 horizons: **332.44 SGD/MWh MAE**.
The best feature-based candidate was **HGB_MAE**, at **369.88**,
compared with the existing model's **405.24**.
These are measured validation improvements, not confirmed general accuracy.
The median beats the current model by 72.80 SGD/MWh on this validation sample.

The 28 September outcomes were already viewed during the presentation work.
Candidate selection was saved before calculating candidate diagnostics on that
day. **Those diagnostics are not a new independent test.** RIDGE_LAGS happens to
have the lowest diagnostic error, but its worse validation result did not select
it. No settings were changed after seeing these results. B2 wins the first
validation day; the median wins the second. Two days do not establish a stable
ranking or statistical significance.

The UI, live service, model registry and promotion/stale/actionable guards are
unchanged. The original presentation experiment, model, predictions and metrics
are retained byte-for-byte. All candidate models, including losing variants,
remain in a separate offline research directory.

## Data, splits and sample counts

The controlled comparison reuses dataset
`b0797a3dfd148a8368507e08a64600154a1d660075aaaebfcd10fa6bc5bb5502`:
23 September 15:00–29 September 16:30 SGT, 300 revision rows but only **289 distinct
periods**, 11 revisions and three missing periods on 23 September. Revisions are
selected by publication/availability; they are never counted as independent
training periods. The original conservative mapping metadata remains unchanged;
the existing evidence covers only the documented 29 September observation window.
Newer local snapshots extend the incomplete 29 September day, not pre-test history.

All boundaries below are midnight SGT, end exclusive. Each model predicts 24
future half-hour intervals (12 hours); the last target interval must end by the
split boundary. A full origin is purged if any target would cross it.

| Fold | Training interval | Examples **per horizon** | Available unique training price periods | Purged train origins | Validation interval | Eligible validation origins / pairs |
| --- | --- | ---: | ---: | ---: | --- | --- |
| 1 | 24 Sep → 26 Sep | 72 | 96 | 24 | 26 Sep → 27 Sep | 24 / 576 |
| 2 | 24 Sep → 27 Sep | 120 | 144 | 24 | 27 Sep → 28 Sep | 24 / 576 |

There are 1,728 and 2,880 horizon-labelled training examples respectively across
24 fitted models; these heavily reuse target periods and are not independent
observations. The reference and all candidates use identical training-origin
eligibility in each fold. Its original test diagnostic uses the second fold's
models, with **120 examples per horizon**, without retraining on validation or
test outcomes.

Pooled validation compares **48 origins × 24 = 1,152 identical pairs** across the
11 eligible methods, representing **94 distinct target periods**. Each horizon
has 48 paired errors. Another 1,152 pairs per method are excluded at split ends.
The diagnostic compares 576 identical pairs, 24 origins and 47 distinct targets;
each horizon has 24 errors and another 576 pairs per method are purged.
B3 has no seven-day history: 1,152 additional validation pairs are excluded for
missing history (2,304 total). The common set including B3 is explicitly zero.

## Fixed candidates and incremental features

The protocol and candidate settings were written before fitting. Selection uses
lowest pooled validation MAE on common pairs, with a lexical tie break. There is
no per-horizon winner selection, random time split, parameter search or clipping
of bad/negative forecasts.

- **CURRENT:** existing 24 direct squared-error histogram gradient boosters,
  40 iterations, at most 15 leaves, L2=1, seed 42, original 16 features.
- **MEDIAN:** one fitted training-target median per horizon; a simple statistical
  forecaster, not a claim of learned price dynamics.
- **PERSISTENCE:** repeat the last known price at the origin for all 24 targets.
- **B1/B2/B3:** unchanged six-period mean, yesterday's target, and seven-day
  matching-period average.
- **RIDGE_LAGS:** ridge alpha=10, median imputation and standard scaling; latest
  price and exact lags 1, 2, 3, 6 and 12 half-hours.
- **RIDGE_DAILY:** add lag 48 and yesterday's target period.
- **RIDGE_ROLLING:** add means and population standard deviations over 6/48 lags.
- **RIDGE_CHANGES:** add lag1−lag2, lag1−lag3 and lag1−lag6 price changes.
- **RIDGE_CALENDAR:** add target half-hour, weekday and weekend flag.
- **HGB_MAE:** absolute-error boosting on that last cumulative feature set, with
  the same 40 iterations / 15 leaves / L2=1 / seed 42. Compared with CURRENT,
  both objective and feature set differ, so the improvement cannot be attributed
  solely to the loss function. Feature effects are isolated within the ridge sequence.

The one-week feature in CURRENT is missing in **100%** of training examples.
Lag 48 is missing in **45.83% / 27.50%** of fold 1/2 examples; 48-lag rolling
features are missing in **52.78% / 31.67%**. The candidate sequence omits the
unusable weekly feature. Adding daily and rolling features to ridge sharply
worsened validation; changes and calendar partly recovered that loss but still
lost to recent lags alone. These failures are retained. This small, shifting sample
cannot establish that seasonal/rolling features are generally harmful.

All imputers, missing indicators and ridge scalers fit training matrices only.
Price/calendar features share the existing feature function; exact timestamp
lookups and as-of history handle gaps and late revisions. No test-day data or
post-test revisions of earlier prices reach selection. Calendar fields are known
at prediction time. Demand/weather were excluded from this controlled study;
there is no long verified demand/weather archive, and the collector's two-hour
weather forecast cannot cover the full prediction window. Future experiments
must verify source publication, receipt and validity before using those fields.

## Overall MAE — SGD/MWh, lower is better

Rows are ordered by validation rank. All non-B3 rows share the same eligible
origins, target timestamps and truth revisions within each split.

| Method | 26 Sep validation | 27 Sep validation | Pooled validation | 28 Sep: seen diagnostic |
| --- | --- | --- | --- | --- |
| MEDIAN | 457.75 | 207.13 | 332.44 | 211.94 |
| HGB_MAE | 425.32 | 314.43 | 369.88 | 224.41 |
| CURRENT | 414.46 | 396.02 | 405.24 | 273.55 |
| PERSISTENCE | 583.84 | 235.24 | 409.54 | 246.01 |
| B1 | 546.46 | 311.11 | 428.79 | 234.31 |
| RIDGE_LAGS | 489.38 | 393.22 | 441.30 | 209.38 |
| B2 | 403.52 | 581.00 | 492.26 | 344.31 |
| RIDGE_DAILY | 441.93 | 1,330.87 | 886.40 | 605.01 |
| RIDGE_CALENDAR | 707.00 | 1,832.38 | 1,269.69 | 538.56 |
| RIDGE_CHANGES | 710.06 | 2,045.36 | 1,377.71 | 670.80 |
| RIDGE_ROLLING | 711.31 | 2,048.17 | 1,379.74 | 671.90 |
| B3 | Unavailable | Unavailable | Unavailable | Unavailable |

## Where the current model's errors occur

| Target horizons | Current validation | Current seen diagnostic | B1 seen diagnostic |
| --- | --- | --- | --- |
| 1-6 | 311.77 | 166.43 | 193.71 |
| 7-12 | 333.79 | 273.06 | 267.21 |
| 13-24 | 487.70 | 327.36 | 238.16 |

Long horizons 13–24 (6.5–12 hours to target start) contribute about **60%** of the
current model's absolute error on both validation and the old diagnostic day.
On that diagnostic day they have **89.20 SGD/MWh more MAE than B1**. Earlier
horizons 1–6 outperform B1. Longer horizons account for the main excess error.

True spike attribution is **unavailable**: there are **zero assessed pairs,
zero known labelled positive/negative targets**, and 1,152 validation / 576
diagnostic pairs with unknown spike labels. That does not mean no spikes occurred.
The unchanged spike rule needs 14 prior matching half-hours within 28 days.
Precision/recall remain null. This study would also require at least 20 distinct
labelled positive and 20 negative targets before reporting those estimates;
that reporting rule does not alter operational spike logic.

A separate descriptive price-tail diagnostic uses the **95th percentile of unique
training-period prices**, frozen separately in each fold: 413.815 and 1,092.742
SGD/MWh. It is **not a substitute spike label**. In validation, 258/1,152 tail pairs
account for 49.11% of current-model error. On the already-seen day, 45/576 tail
pairs (three actual periods) have MAE 942.14 and account for 26.91% of error.
The remaining 531 pairs have MAE 216.89 and account for 73.09%. Error is therefore
not confined to extreme prices. HGB_MAE improves total error but has worse tail
MAE on that day (981.79) than CURRENT (942.14) or B1 (840.16); no tail/spike
improvement claim follows from its overall result.

## MAE by horizon: pooled validation

Training samples per horizon: **72 / 120** in folds 1/2. Evaluation: **48 identical
pairs per horizon**. All 24 horizons are shown. Full per-horizon results for every
candidate, including the losing ridge variants and B2, are in
[the CSV](accuracy-study-horizons-2026-09-29.csv).

| Horizon | Lead, hours | Current | Median | HGB MAE | B1 | Persistence |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.5 | 301.08 | 298.80 | 271.76 | 299.16 | 207.49 |
| 2 | 1 | 318.03 | 299.85 | 281.06 | 338.52 | 265.27 |
| 3 | 1.5 | 313.19 | 282.05 | 269.25 | 351.61 | 305.52 |
| 4 | 2 | 306.08 | 275.61 | 271.72 | 379.47 | 324.89 |
| 5 | 2.5 | 316.51 | 264.17 | 268.76 | 390.73 | 323.53 |
| 6 | 3 | 315.77 | 261.73 | 271.74 | 403.69 | 338.51 |
| 7 | 3.5 | 311.87 | 257.90 | 282.07 | 411.65 | 361.21 |
| 8 | 4 | 306.16 | 258.19 | 276.16 | 418.89 | 359.64 |
| 9 | 4.5 | 289.44 | 248.80 | 261.19 | 424.81 | 375.52 |
| 10 | 5 | 357.91 | 319.64 | 330.53 | 516.53 | 425.21 |
| 11 | 5.5 | 374.05 | 311.90 | 328.30 | 519.38 | 415.21 |
| 12 | 6 | 363.32 | 308.56 | 326.31 | 526.21 | 428.37 |
| 13 | 6.5 | 357.99 | 326.13 | 352.48 | 542.79 | 488.70 |
| 14 | 7 | 401.63 | 359.10 | 373.98 | 557.93 | 553.49 |
| 15 | 7.5 | 388.42 | 358.67 | 389.10 | 535.34 | 562.64 |
| 16 | 8 | 431.48 | 353.09 | 401.01 | 499.02 | 542.24 |
| 17 | 8.5 | 434.83 | 340.15 | 394.10 | 463.60 | 524.50 |
| 18 | 9 | 437.40 | 340.99 | 403.14 | 425.10 | 499.13 |
| 19 | 9.5 | 452.36 | 355.25 | 442.63 | 395.98 | 453.82 |
| 20 | 10 | 492.26 | 369.46 | 440.44 | 370.90 | 429.46 |
| 21 | 10.5 | 549.74 | 418.54 | 511.86 | 380.78 | 426.45 |
| 22 | 11 | 625.76 | 449.92 | 572.20 | 383.57 | 430.39 |
| 23 | 11.5 | 629.08 | 459.24 | 570.55 | 374.06 | 400.73 |
| 24 | 12 | 651.40 | 460.90 | 586.68 | 381.14 | 387.08 |

## MAE by horizon: already-seen diagnostic day

Training samples per horizon: **120**. Evaluation: **24 identical pairs per
horizon**. This table was produced after freezing selection and is not used to
choose a candidate.

| Horizon | Current | Median | HGB MAE | B1 | Persistence |
| --- | --- | --- | --- | --- | --- |
| 1 | 169.64 | 110.61 | 182.77 | 157.68 | 125.27 |
| 2 | 173.67 | 111.41 | 169.80 | 178.72 | 144.43 |
| 3 | 169.15 | 112.94 | 157.83 | 195.60 | 164.20 |
| 4 | 161.64 | 114.48 | 147.10 | 203.82 | 181.81 |
| 5 | 156.65 | 115.76 | 154.31 | 210.34 | 194.99 |
| 6 | 167.83 | 117.31 | 138.08 | 216.13 | 204.76 |
| 7 | 157.50 | 118.80 | 118.04 | 220.10 | 210.98 |
| 8 | 162.56 | 118.92 | 115.91 | 220.47 | 218.57 |
| 9 | 200.64 | 162.12 | 144.55 | 260.78 | 266.16 |
| 10 | 364.86 | 204.29 | 193.77 | 290.16 | 312.79 |
| 11 | 376.16 | 239.94 | 218.05 | 302.51 | 348.30 |
| 12 | 376.64 | 273.20 | 234.39 | 309.27 | 377.06 |
| 13 | 338.35 | 274.51 | 246.50 | 282.43 | 364.08 |
| 14 | 280.87 | 281.78 | 260.93 | 263.68 | 356.64 |
| 15 | 302.02 | 294.33 | 301.48 | 258.46 | 314.00 |
| 16 | 272.63 | 286.43 | 291.71 | 234.57 | 250.84 |
| 17 | 239.87 | 261.77 | 279.99 | 203.78 | 210.49 |
| 18 | 280.78 | 267.46 | 283.97 | 210.01 | 200.66 |
| 19 | 326.05 | 271.28 | 235.30 | 213.67 | 232.09 |
| 20 | 281.52 | 271.19 | 303.46 | 200.86 | 259.78 |
| 21 | 338.73 | 271.46 | 281.75 | 213.13 | 237.89 |
| 22 | 401.51 | 271.39 | 281.86 | 239.37 | 214.95 |
| 23 | 430.65 | 267.79 | 359.56 | 260.45 | 242.44 |
| 24 | 435.39 | 267.38 | 284.67 | 277.50 | 271.18 |

## Historical importer audit and concrete acquisition plan

The only additional permitted official file found was the cached **20 September
2026 EMC USEP CSV**. It was copied through the existing immutable `snapshot --kind
emc` and `prepare` commands into:

`/home/bryan/CS203_T4/wattly-f3-research/datasets/emc-4d9f3b61a510d5845ec4`

It has **48 unique periods**, zero gaps, duplicates or parsing failures, and
**no historical publication/availability timestamps**. Operational as-of eligibility
is zero. It is isolated from the collector by missing intervening days, so it was
preserved separately and cannot extend this operational training experiment.
The importer treats EMC files as final-price labels with unknown vintages;
settlement status must be established from the supplied product's provenance.
No synthetic fixture or repeated revision was added as real history.

Required data:

1. **Price series:** Uniform Singapore Energy Price (USEP), SGD/MWh, all 48 periods
   of every Singapore calendar day, with original `DATE`, `PERIOD` and
   `USEP ($/MWh)` columns. Preserve negative/zero prices and original source files.
   Period 1 starts 00:00 and period 48 starts 23:30 SGT. The importer also accepts
   documented Date/Period aliases, ISO dates and slash dates; it rejects bad periods,
   nonfinite prices and unknown headers. Never substitute LCP, RUSEP, demand or a
   retail tariff for USEP.
2. **Operational vintages:** for each price/revision, genuine source publication
   and first availability/receipt instants with time zones, revision identity,
   provisional/final status, period mapping and provenance. A final-price CSV alone
   cannot reconstruct what was knowable live. Do not invent receipt times from
   period ends. The existing `--retrospective` mode explicitly assumes period-end
   availability for final files; any such run must remain separately labelled and
   exploratory, and cannot establish operational accuracy.
3. **Dates for a meaningful next dataset:** acquire permitted historical files for
   **3 August 2025–27 September 2026 inclusive**: 421 days, **20,208 distinct
   half-hour periods** before revisions. This supports 28 warm-up days
   (3–30 Aug 2025), 365 training days (31 Aug 2025–30 Aug 2026) and 28 validation
   days (31 Aug–27 Sep 2026). Keep 28 Sep out of selection. This is the existing
   trainer's data threshold, not a promise that approval will pass.
4. **New confirmation:** freeze the model/settings and start an unseen test at the
   first Singapore midnight after the data and artifact are ready. If ready on
   29 September, an example is **30 Sep → 25 Nov 2026**, end exclusive: 56 days /
   2,688 periods. Save predictions before outcomes, preserve later revisions as
   separate truth vintages, and never recycle 28 Sep as an untouched test.
   The 28/29 Sep observations may provide as-of input context for future origins;
   they do not select the settings in this study.
5. **Access and import:** use EMC's permitted Market Data Download, selecting
   **Uniform Singapore Energy Price and Demand Forecast**, in ranges of at most
   31 days. A previous bulk request returned 403; no access-control bypass was
   attempted. Obtain an authorized export if that restriction remains. Save its
   URL, requested dates, retrieval time, product/status and checksum. Import each
   original file separately, then prepare a new combined dataset. Inspect all 48
   slots/day, overlaps, gaps, conflicting final truths, units and null vintages
   before training. Preserve failures; never fill missing periods with invented
   observations. The current importer already supports these files.

Example for a permitted archive file (use new output directories):

```bash
source /home/bryan/CS203_T4/wattly-f3-demo/runtime.env
research_home=/home/bryan/CS203_T4/wattly-f3-research
"$WATTLY_DEMO_PYTHON" -m wattly_ml.cli snapshot /path/to/permitted-emc-export.csv \
  --kind emc --source-url 'https://www.nems.emcsg.com/nems-prices#market-data-download' \
  --output "$research_home/historical-snapshots"
# Substitute the exact printed snapshot directory; add each explicitly audited snapshot.
"$WATTLY_DEMO_PYTHON" -m wattly_ml.cli prepare \
  "$research_home/historical-snapshots/emc-SNAPSHOT_DIGEST" \
  --output "$research_home/datasets/emc-history-v1"
```

Historical demand would additionally need issue/receipt times for its forecast
vintage. Weather needs issue/update/receipt and validity intervals for every
forecast revision, including daily forecasts for targets beyond the two-hour
feed. Missing archives remain unavailable; eventual actual weather cannot be
substituted for historically available forecasts.

## Reproduction, checks and retained artifacts

Run from the repository root; the command refuses an existing output directory:

```bash
source /home/bryan/CS203_T4/wattly-f3-demo/runtime.env
OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 "$WATTLY_DEMO_PYTHON" -m wattly_ml.cli compare-models \
  "$WATTLY_DEMO_DATASET" --reference "$WATTLY_DEMO_EXPERIMENT" \
  --output /home/bryan/CS203_T4/wattly-f3-research/reproduction-1
```

The retained run is `/home/bryan/CS203_T4/wattly-f3-research/2026-09-29-price-models-v1`.
It contains the predeclared protocol, sealed selection, every fold's models and
predictions, all exclusions, pooled validation, separate old-test diagnostics,
per-horizon CSV and complete summary. Failed runs retain `failure.json` and partial
evidence; complete directories are never overwritten. Models are marked research
only and ineligible for production; no approved pointer is written.

Independent checks recomputed every method/horizon MAE from identical saved pairs,
verified shared targets/truth and checked **384 fitted imputers** against
training-only medians (plus every ridge scaler). Regression tests change held-out
prices and inject late revisions: all training matrices/labels and validation
selection remain unchanged. The current model's saved diagnostic predictions
match the reference to 1e-9, and all reference-file hashes are preserved.
Verification evidence is `../price-models-v1-verification.json` next to the run;
final Python checks are saved as `python-tests-final.log` and
`python-tests-final.xml` in that same research parent directory.
**All 15 Python tests passed**, including the new paired-score, feature-difference,
selection-isolation and immutable-reference checks. `git diff --check` passed.

Machine-readable results: [summary](accuracy-study-2026-09-29.json) and
[all per-horizon scores](accuracy-study-horizons-2026-09-29.csv).

**Remaining limits:** very little history, two correlated validation days, no new
independent test, no usable B3 coverage, no qualified spike event labels, no
operational historical weather/demand study, and no production approval.

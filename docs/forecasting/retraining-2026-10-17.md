# Model retraining — 17 October 2026

Retrained **HGB_REGULARISED** and **AR6_HUBER** with their existing settings.
Latest artifacts use eligible collector data through **17 October, 23:30 SGT**.
Both have `researchOnly=true` and `productionEligible=false`. No model was promoted.

## Same-day comparison with the October 13 models

Reserved **October 16**, the latest complete elapsed Singapore calendar day,
before fitting. Evaluation models used only observations published and available
before **October 16, 00:00 SGT**, with training starting September 24.
The comparison models are the previous run's actual `latest-fit` artifacts,
trained through **October 13, 04:00 SGT**. Their checksums match the saved
October 13 verification record. Model settings and choices were unchanged.

All scores use the same **576 eligible pairs**, comprising 24 origins and
24 half-hour horizons over 47 distinct target periods. Origins whose full
forecast window crosses midnight are excluded. Overlapping forecast pairs
are not independent observations.

| Method | October 16 MAE, SGD/MWh |
| --- | ---: |
| Retrained HGB_REGULARISED | 90.68 |
| October 13 HGB_REGULARISED | 88.55 |
| Retrained AR6_HUBER | 126.18 |
| October 13 AR6_HUBER | 124.91 |
| Training median | 129.88 |
| Persistence | 143.92 |
| B1 | 141.01 |
| B2 | 225.37 |

Retraining increased HGB error **2.41%** and AR error **1.02%** relative to their
October 13 versions on this same day. Both retrained models beat the listed
baselines, but neither improved on its predecessor. Scores from the prior
October 12 test day cannot be compared directly with these October 16 scores
to measure improvement.

After evaluation, separate latest fits incorporated all eligible snapshot data.
Those fits include October 16 and need fresh evaluation; the scores above apply
only to the saved `evaluation-fit` versions. No hyperparameters were retuned.

## Validation before training

The read-only SQLite backup, including WAL, was acquired at **23:38:27 SGT**.
It contains **1,154 records and 1,118 distinct half-hour periods**, an increase
of 235 records and 224 periods over October 13. There are 36 revisions,
no exact duplicate rows, no parsing failures and no availability timestamps
preceding publication. The copied database's SQLite `quick_check` returned `ok`.

The existing parser checked finite prices, aligned half-hours, source/payload
timestamps and publication/availability provenance. Calendar coverage was
audited and saved before training. A new **seven-period gap on October 14,
08:30–11:30 SGT**, remains missing. There are 51 missing half-hours within
September 24–October 16, and 1,187 across the full range including isolated
older observations. Missing target windows were excluded, without filling
target prices. HGB imputation and AR scaling fitted training data only.

The archived mapping check again matched all 24 September 29 reference periods.
Its evidence does not independently verify all later periods; conservative
dataset mapping flags remain unchanged. Prices remain provisional.

Latest training counts increased from **777 to 976 origins per horizon** for
HGB and **858 to 1,076 one-step examples** for AR. Evaluation fits used 881
and 981 respectively. Features remain price/calendar only.

## Saved artifacts and checks

Artifacts are local and excluded from Git:

- [Latest HGB models](../../.local/retraining-2026-10-17/models-and-evaluation-v1/latest-fit/hgb/candidates.joblib)
- [Latest AR model](../../.local/retraining-2026-10-17/models-and-evaluation-v1/latest-fit/ar/candidates.joblib)
- [Full summary](../../.local/retraining-2026-10-17/models-and-evaluation-v1/summary.json)
- [Saved predictions](../../.local/retraining-2026-10-17/models-and-evaluation-v1/held-out-test/predictions.jsonl)
- [Per-horizon results](../../.local/retraining-2026-10-17/models-and-evaluation-v1/horizons.csv)
- [Verification results](../../.local/retraining-2026-10-17/models-and-evaluation-v1/verification.json)
- [Training record](../../.local/retraining-2026-10-17/run.py)
- [Runnable verification](../../.local/retraining-2026-10-17/verify.py)

Canonical dataset SHA-256:
`0906d9218cf0e7aecb1dde1dc506a619ff397f5f26042d9b889070d1b263f874`.

Independent saved-prediction checks passed for MAE, identical eligible pairs,
canonical actual prices, target alignment and purged boundaries. Previous-run
file hashes and the dataset digest remain unchanged. Both latest artifacts
passed checksum/dependency checks, reloaded and produced 24 finite forecasts.
AR converged in 22 iterations for evaluation and 21 for the latest fit; no
convergence warnings occurred. The existing `Unknown solver options: iprint`
compatibility warning is retained in the AR manifests.

No application regression suite was run; no application code was changed.
The collector and serving artifacts were untouched. Short, gappy provisional
history and a single historical test day do not establish future accuracy or
production readiness.

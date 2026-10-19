# Model retraining — 13 October 2026

Retrained the previously selected **HGB_REGULARISED** and **AR6_HUBER** with their
existing settings. Both latest fits use eligible collector observations through
**13 October 2026, 04:00 SGT**. They are experimental artifacts, with
`researchOnly=true` and `productionEligible=false`; no model was promoted.

## October 12 evaluation

Separate evaluation models were trained only on data published and available
before **12 October, 00:00 SGT**. Model choices and parameters came from the saved
October 6 studies; this run performed no selection or tuning on October 12.
After evaluation, a separate latest fit incorporated the remaining eligible data.

All scores below use the same **576 eligible forecast pairs**, comprising
24 origins with 24 half-hour horizons. Target intervals crossing midnight are
excluded. Overlapping forecasts cover 47 distinct target periods and are not
576 independent price observations.

| Method | October 12 MAE, SGD/MWh |
| --- | ---: |
| Retrained HGB_REGULARISED | 202.86 |
| Previous frozen HGB_REGULARISED | 183.17 |
| Retrained AR6_HUBER | 176.00 |
| Previous frozen AR6_HUBER | 182.46 |
| Training median | 136.25 |
| Persistence | 148.58 |
| B1 | 146.50 |
| B2 | 281.58 |

AR error decreased **3.54%** relative to its predecessor. HGB error increased
**10.75%**. Both retrained models lost to the median, persistence and B1 on this
day. These results do not justify deployment or selecting a new model using the
test outcomes. Scores apply to `evaluation-fit`, not to `latest-fit`, which has
incorporated this test day and needs separate future evaluation.

## Data and artifacts

The read-only SQLite backup, including WAL, was acquired at **04:04:30 SGT**.
It contains **919 rows, 894 distinct periods, 25 revisions and no parsing
failures**. There are 1,180 missing half-hours across the entire historical
range; 44 are within September 24–October 12. Missing targets are excluded;
model preprocessing uses training data only. October 13 is incomplete.
The archived September 29 mapping comparison passed again for all 24 periods;
the original conservative manifest flags remain intact.

The latest HGB fit has **777 training origins per horizon**; AR has **858
one-step training examples**. Evaluation fits used 721 and 802 respectively.
Features remain price/calendar only, with provisional-price labels.

Artifacts are local and excluded from Git:

- [Latest HGB models](../../.local/retraining-2026-10-13/models-and-evaluation-v3/latest-fit/hgb/candidates.joblib)
- [Latest AR model](../../.local/retraining-2026-10-13/models-and-evaluation-v3/latest-fit/ar/candidates.joblib)
- [Full summary](../../.local/retraining-2026-10-13/models-and-evaluation-v3/summary.json)
- [Saved predictions](../../.local/retraining-2026-10-13/models-and-evaluation-v3/held-out-test/predictions.jsonl)
- [Per-horizon results](../../.local/retraining-2026-10-13/models-and-evaluation-v3/horizons.csv)
- [Artifact verification](../../.local/retraining-2026-10-13/models-and-evaluation-v3/verification.json)
- [Runnable training record](../../.local/retraining-2026-10-13/run.py)

Canonical dataset SHA-256:
`31719462fab22c2c0699c9aed75123e85c4613cbad73aeede0e73222b53448fe`.

## Verification and limits

Checks passed for independently recomputed MAE, identical eligible pairs, purged
target boundaries, unchanged previous-study hashes and unchanged dataset digest.
Both latest artifacts reloaded successfully and produced 24 finite predictions
from the snapshot's available history. No AR convergence warning occurred.
The existing SciPy/scikit-learn combination emitted `Unknown solver options:
iprint`; this warning is retained in the AR manifests.

This is a single historical holdout day with short, gappy provisional-price
history. Future accuracy, production readiness and spike accuracy remain
unestablished. The collector and existing serving artifacts were not modified.
No application regression suite was run; this task changed no application code.

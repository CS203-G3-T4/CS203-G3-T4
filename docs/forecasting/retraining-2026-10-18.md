# AI retraining — 18 October 2026

Retrained the active **schema2, 24-horizon HistGradientBoosting** pipeline with
unchanged settings on the latest collector snapshot. Saved an evaluation model
and a separate latest fit. Both remain research artifacts. The earlier AI model
was retained; no serving pointer or approval setting changed.

## Data validated before fitting

The read-only SQLite backup was acquired at **15:14:59 Singapore time**. SQLite
`quick_check` returned `ok`. It contains **1,187 records, 1,149 unique half-hours
and 38 revisions**, including **31 new half-hours and two additional revisions**
since the October 17 snapshot. Latest observed period: October 18, 15:00 SGT.
No parsing failures, exact duplicates or availability timestamps preceding
publication were found. Prices remain provisional; gaps were not filled.

The full raw date range includes isolated August/September observations and
1,187 missing half-hours; this is not continuous historical training coverage.
The existing calendar audit lists every gap and complete/incomplete day.
The mapping evidence remains the recorded September 29 comparison, without
claiming independent verification of every later period. Genuine origin-known
expected-demand vintages remain absent. Schema2 represents that input as missing;
it does not substitute subsequently observed demand. Holiday coverage is 100%.

## Fixed evaluation protocol and results

The evaluation fit used eligible training examples from **September 24 through
October 10, 00:00 SGT**, then seven earlier validation days through October 17.
October 17 was reserved as the new historical holdout before fitting. No new
hyperparameter search or test-based selection occurred. Imputers fitted training
data only; training targets and source availability were bounded by the cutoff.

The original July 29–September 23 protected test interval and September 28
presentation day were removed from training inputs and labels. The new October 17
holdout also remains excluded from the latest fit. Historical forecasts are
reconstructed today; they are not claims of actual past serving.

All-method comparisons use identical eligible origin/horizon pairs:

| Method | October 10–16 validation MAE | October 17 holdout MAE |
| --- | ---: | ---: |
| Retrained AI | 201.64 | 141.22 |
| Previous AI | **178.99** | **131.24** |
| B1 | 246.01 | 170.21 |
| B2 | 260.80 | 177.41 |
| B3 | 192.26 | 231.13 |

Units: **SGD/MWh; lower is better**. Validation has **3,351 common pairs** across
146 origins and 220 distinct targets, out of 8,064 scheduled pairs (41.56%). B3
was selected from the earlier validation period. The retrained AI loses to B3
and the previous AI on that validation coverage.

The holdout all-method comparison has only **24 common pairs: one origin and
24 targets**, out of 1,152 scheduled pairs. The October 14 gap removes most B3
inputs; split-boundary purging removes origins whose full 12-hour target window
crosses midnight. This narrow overlap is insufficient for a broad ranking claim.

A separately labelled comparison excluding B3 covers **576 identical pairs**
across 24 origins and 47 distinct targets (50% of scheduled pairs):

| Method | October 17 MAE, broader coverage without B3 |
| --- | ---: |
| Retrained AI | 125.75 |
| Previous AI | **90.22** |
| B1 | 118.09 |
| B2 | 91.13 |

On this comparison the retrained AI has **39.38% higher error** than the previous
AI. Do not compare these values with B3's different-coverage score above.
The previous AI used twelve months of final-price training data; this candidate
uses short recent provisional history. Differences cannot be attributed solely
to the additional 31 periods. Overlapping forecast pairs are not independent
events; one day is not evidence of future reliability or household savings.

## Latest fit, checks and artifacts

After evaluation, a separate latest model incorporated eligible snapshot data
through the **October 18, 15:00 SGT cutoff**, excluding all protected intervals.
It has **861 training origins per horizon**, versus 552 for the evaluation fit.
**The reported accuracy belongs only to the evaluation fit.** The latest weights
have no independent accuracy score and remain `productionEligible=false`.

Both artifacts passed checksum/dependency/schema validation, reloaded, and
generated 24 finite predictions. A separate verifier recalculated MAE and every
per-horizon score from saved predictions, checked canonical actuals, identical
comparison pairs, target alignment, split boundaries and unchanged prior-model
hashes. Six targeted Python tests passed; one upstream deprecation warning remains.
No application source changed, so Java/frontend/database/browser suites were not
repeated for this training run.

Evidence: [independent verification](evidence/2026-10-18-retraining/verification.json),
[pre-training audit](evidence/2026-10-18-retraining/data-audit-before-training.json),
[frozen protocol](evidence/2026-10-18-retraining/protocol.json) and
[test result](evidence/2026-10-18-retraining/tests.json).

Local immutable outputs are under `.local/retraining-2026-10-18-v1/`:

- `models-and-evaluation-v1/models/schema2-collector-evaluation-20261018/`
- `models-and-evaluation-v1/models/schema2-collector-latest-20261018/`
- `models-and-evaluation-v1/{validation,held-out-test}/predictions.jsonl`
- `runtime/training-attempts/`: durable SUCCESS research attempt, promotion RETAINED.
  Audit API synchronization remains pending; no live application API was contacted.

The saved [training script](evidence/2026-10-18-retraining/run.py) and
[verification script](evidence/2026-10-18-retraining/verify.py) record the exact run.
They execute from the local run directory alongside `dataset/` and its audit;
the prior trusted model path is recorded in the protocol. Training rejects
existing output directories. Rechecking existing outputs does not fit models:

```bash
PYTHONPATH=ml/src OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 MKL_NUM_THREADS=1 \
  /home/bryan/CS203_T4/wattly-f3-demo/tools/venv/bin/python \
  .local/retraining-2026-10-18-v1/verify.py
```

Dataset SHA-256 and model checksums are in the saved evidence. The collector,
live application database, previous model artifacts and serving configuration
were unchanged. Production eligibility and promotion checks remain enforced.

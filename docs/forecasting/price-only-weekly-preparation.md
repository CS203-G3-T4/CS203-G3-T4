# Price-only weekly retraining: offline preparation [CSDT4-59]

This explicit rehearsal adapts fitting/evaluation to the pinned experimental
PRICE_183D model. It does **not** invoke the older `weekly` command, install a
timer, call an admin API, write `current.json`, change serving pins or update a
live trial. It is preparation, not completion of scheduled operational adoption.

## One attempt

1. Pin an authorized trusted local champion manifest. Its model checksum,
   dependencies, holiday calendar, 19-feature schema and price-only contract
   must validate before deserializing its fitted pipelines.
2. Load an immutable prepared dataset with original publication/receipt times.
   Only provisional prices with verified mapping are allowed. The explicit
   `EMC_RT_20260929_24_PERIODS` option reuses the existing narrowly scoped zero-offset
   NEMS mapping evidence; it does not relabel raw `UNVERIFIED_FLOOR` metadata or
   authorize another source/offset. Historical final-price archives with unknown
   availability are not silently treated as contemporary receipts.
3. Fit exactly one clone of the champion's 24 pipelines over the preceding
   183-day window. Copy hyperparameters rather than search. Use the same shared
   completed-price/calendar/holiday feature function as inference; solar and
   expected demand remain absent. All 24 training targets must end and their
   truths must be received by the training cutoff. Protected prices are removed
   from labels and lag inputs, without filling the resulting gaps.
4. Reconstruct forecasts from candidate, current model and B1/B2/B3 on identical
   origins/targets. Freeze truth to one explicit receipt cutoff. Report own
   eligible coverage and their common intersection, MAE by horizon, RMSE, and
   ordinary/spike/unassessed groups. Spike counts use statistical proxy labels;
   they are not independent genuine-event labels or household notifications.
5. Save protocol, attempt record, candidate bundle, paired predictions and report
   in a new separate directory. Recheck source hashes. A worse candidate still
   leaves useful evidence. A better MAE result is **review-only**: adoption remains
   false, including when the non-inferiority diagnostic passes.

The current model must have finished fitting before the comparison starts. The
new candidate is fitted now using earlier data; this is an explicitly reconstructed
historical comparison, not a claim it was actually served before it existed.
Its saved `trainingDate`/`usableFrom` remain the explicit attempt time. There is
no post-evaluation refit, threshold change, baseline-ranking search or promotion.

## Explicit invocation, no timer activation

Use the dedicated Python environment and trusted local prepared dataset/model.
Replace all example paths and dates with a reviewed weekly cutoff. Do not print
or commit private inputs. The example evaluates seven days and allows outcome
maturity before scoring; it does not certify that these inputs presently exist.

```sh
PYTHONPATH=ml/src OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 MKL_NUM_THREADS=1 \
  /path/to/dedicated/python -m wattly_ml.price_only_weekly \
  --dataset /private/immutable-prepared-dataset \
  --champion /private/trusted-runtime/models/price-only-version \
  --champion-manifest-sha256 EXPECTED_MANIFEST_SHA256 \
  --output /private/separate-weekly-attempts/unique-attempt \
  --training-cutoff 2026-11-08T00:00:00+08:00 \
  --validation-end 2026-11-15T00:00:00+08:00 \
  --as-of 2026-11-16T00:00:00+08:00 \
  --protected-start 2026-07-29T00:00:00+08:00 \
  --protected-end 2026-09-29T00:00:00+08:00
```

For the already documented NEMS mapping only, add
`--mapping-evidence EMC_RT_20260929_24_PERIODS`. Other mapping evidence is rejected.
Changing the validation boundaries changes the study: reserve a subsequent
untouched test period for any actual adoption claim. Weekly repetitions are a
predeclared process, not permission to tune repeatedly on the same holdout.

The 48-origin minimum allows a small correctness rehearsal; it is **not** proof
of a complete 183-day training history, a statistically adequate validation or
weekly consistency. Training coverage, exclusions, validation length and pair
counts are reported. Incomplete data and zero spike support cannot become an
accuracy/sensitivity claim. Unknown labels stay unassessed.

Outputs must be separate from inputs/champion and outside `WATTLY_ML_HOME` when
set. The existing output directory is never reused. Trust only authorized local
joblib inputs: hashes detect changes, not malicious serialized code. Handled
training/evaluation failures leave a FAILED attempt; a hard process/host kill can
leave RUNNING evidence, which must not be interpreted as success or overwritten.

## Why the same data points help

Pair **issue time, target half-hour, horizon and truth vintage**, not merely the
same calendar date. Compare a 13:00 forecast for 14:00 with another 13:00 forecast
for 14:00, not a 13:30 update with more recent information. Fit neither model on
the evaluation outcomes. Common points control for different market conditions;
they do not remove correlated errors, uncertainty or the effect of changing a
model midway through a fixed-model trial.

The existing fixed trial can continue while separately versioned candidates are
evaluated on the same points. A changing weekly-model policy could also be tested,
with versions, update rules and gates defined in advance and separately identified.
Do not pool its results under the existing frozen trial/model ID.

## Verification and remaining activation work

The new tests cover fit/save/load/HTTP inference, train/serve feature parity,
auxiliary-input exclusion, future outcomes and late revisions, shared coverage,
misaligned target rejection, source pins, protected periods, forbidden output
locations, source mutation, immutable outputs, handled failures and better/worse
candidate diagnostics without adoption. Existing price-only serving, scheduled
operations and real-HTTP audit tests remain passing; counts are in the local
verification receipt rather than falsely attributed to a full-suite CI run.

A separate rehearsal used **synthetic prices** and the actual pinned current
model's 24 pipeline templates. It fitted 503 origins, compared 48 common pairs,
loaded the saved candidate and returned 24 finite HTTP forecast points. All
pipeline settings and original source model hashes were unchanged. This checks
compatibility, not real price accuracy, savings improvement or full data readiness.
Generated inputs/models/reports remain private outside Git.

Before operational activation, connect fresh immutable input acquisition to this
new entry point, decide data-quality/support and price/spike/savings acceptance
gates, persist its attempt/result through the existing admin audit contract, and
test an isolated adoption/rollback handoff for the hash-pinned serving setup.
Then review the scheduler wiring and operating schedule. The current one-time
spike study and fixed live trial remain unchanged; the older timer is not enabled.
Task59 remains preparation-only and In Progress.

## Follow-through implementation

[Inactive runtime and sandbox integration](price-only-weekly-runtime.md) now
provides fresh SQLite acquisition, weekly claims, existing API audit delivery
and retry, staged units and a real HTTP adoption/rollback rehearsal. This
supersedes the earlier list of missing integration code; it does not supersede
the activation, data-coverage or acceptance boundaries. No service was activated.

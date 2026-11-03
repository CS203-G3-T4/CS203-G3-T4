# CSDT4-35: no-solar demand readiness — 3 November 2026

Follow-up: the [shared recipe and bounded trainer](price-demand-training-2026-11-03.md)
are now prepared. The evidence below records the earlier input-only preflight.
No real candidate or new prospective protocol has been created.

Bryan selected preparation of a separate price-plus-demand experiment without
solar. The current price-only model, existing solar experiment and live trial
remain unchanged. This delivers an offline input preflight, not a fitted model,
an accuracy result, a newly registered prospective experiment or task completion.
Jira was read to confirm the retained expected-demand requirement and not edited.

## What is prepared

`wattly_ml.price_demand_readiness` validates original Java saved snapshots with
either a direct market object or the older `marketInputs` wrapper. It never
requires, reads or substitutes solar values. The candidate retains complete
price/calendar/holiday inputs plus all24 target-matched expected-demand forecasts.
Every demand vintage must have been published and received by the original issue
time. Missing/future/invalid/conflicting demand fails; it is not imputed as zero.

Candidate and matched price-only control use exactly the same completed-price
history and issue times. The already available current-period quote is excluded,
matching the current price-only recipe. The control has no demand/solar auxiliary
attributes. All three simple baselines must have full horizon coverage. Original
price mapping and receipt times are validated, including the existing evidenced
NEMS floor mapping; this is reuse of that evidence, not a new provider verification.

Only fresh original LIVE issues from the requested model version qualify. The
first eligible issue per half-hour is retained; repeated updates, replay/demo,
stale and unavailable issues do not create training support. Source counts are
separate from distinct origins. Elapsed12-hour maturity is reported separately
from actual received outcomes: this tool does not export or inspect truth, and
`readyToFit` always remains false until a separate registered fit/evaluation path
has the required input and actual-receipt support.

## Current evidence

An independent read-only PostgreSQL instance used a cold copy of the already
stopped local database. The original cluster was cleanly shut down, had no
external tablespaces/recovery mode, and its complete file-content hash was
identical before and after inspection. Its app/database services stayed stopped.
No app, timer, upstream collector, live database or frozen model/trial was changed.

The database inventory contains4632 demand revisions, with the latest recorded
receipt at3November01:02:56 Singapore. These are overlapping target/vintage rows,
not4632 independent supervised examples. Metadata alone does not prove complete
original-input or received-outcome support for every issue.

Raw input inspection was restricted to **30October15:00 through1November07:00
Singapore, end exclusive**. No raw inputs from the reserved1–8November spike
validation cohort were read, and no actual-price outcomes or predictions were
exported/scored. The unchanged reserved evaluation remains separate.

| Historical input-only scope | Original saved runs | Eligible distinct half-hours | Other results |
|---|---:|---:|---|
| Current price-only model | 87 | 29 | 58 repeated updates; all29 retained origins have complete original target-demand contracts. |
| Older solar-model market snapshots, with solar ignored | 155 | 52 | 102 repeated updates,1 missing-demand exclusion. This is a separate contract check, not membership in the new experiment or a pooled training cohort. |

There were242 inspected runs in total. The issue-file SHA256 is
`d34c79c351ea43f2a9481042d59318990ad11c8d7d957737f1380a647398a04c`.
Inputs, private database copies and detailed operator receipts remain outside Git.
No supervised outcome-completeness count or price/warning/savings score is claimed.

## Reproduce the input preflight

Use an authorised private JSONL export of saved forecast runs with `id`, `asOf`,
`mode`, `modelVersion`, `stale`, `status`, and the original `input_snapshot` object
under `inputs`. Preserve `isDemo` where present, and exclude demo rows at export.
Do not reconstruct missing demand from later forecasts or join post-event actual
demand onto old prices. Export only the intended inspection window; do not read
reserved or future evaluation inputs just to run this check.

```sh
PYTHONPATH=ml/src python -m wattly_ml.price_demand_readiness \
  --issues /private/pre-reservation-issues.jsonl \
  --start 2026-10-30T15:00:00+08:00 \
  --end-exclusive 2026-11-01T07:00:00+08:00 \
  --as-of ACTUAL_TIMESTAMP_WITH_OFFSET \
  --model-version price-only-183d-20261031-local-v1 \
  --mapping-evidence EMC_RT_20260929_24_PERIODS \
  --output /private/new-price-demand-readiness.json
```

The output is aggregate metadata only and write-once. The explicit caller-supplied
time avoids a hidden system clock. This command has no database/network access,
model fitting, serving configuration, timer activation or promotion operation.

## Replacement experiment plan — draft, not registered

The original [solar-demand protocol](demand-candidate-protocol-2026-10-30.json)
requires the old solar-model issues. The current model does not add to that cohort;
its480-origin minimum is unmet. Waiting until its dates will not repair that gap.
Its13–28November schedule must not be advertised as the new no-solar study.

Before registering the replacement:

1. Resolve the ongoing authorised demand-source/collection arrangement with
   Jonathan. Existing stored data can be inspected; this change makes no upstream
   requests and does not restart collection. Verify receipt-stamped24-target
   capture in the actual deployment where the app will run.
2. Choose and freeze a new cohort, dates, exact input policy, comparator/model
   hashes and one-fit stopping rule **before** its holdout starts. Start the new
   study after the reserved8November score. Do not silently reuse the old cohort
   or claim a historical preflight is a prospective test.
3. Allow approximately two weeks of complete training issues (at least480 distinct
   origins after exclusions and split-boundary purging), one explicit fit window,
   then two untouched test weeks (also at least480 complete origins). Exact dates
   remain unset while the app is stopped and ongoing capture is undecided. These
   minima are a proposed support floor, not a guarantee of sufficient spike events.
4. The separately versioned **completed-price plus mandatory as-issued demand**
   policy and guarded trainer are now prepared; see the follow-up above. Real
   registration, sufficient data and fitting remain outstanding. Do not set a
   demand field on the frozen price-only model and attribute old results to it.
5. Fit one demand candidate plus one matched price-only refit using identical
   origins, prices and actuals. The frozen current model and B1/B2/B3 are additional
   controls. Freeze all artifacts before the holdout. No grid search or repeated
   tuning after results are seen; keep the current model on an unsuccessful result.
6. Score identical received-outcome pairs overall, ordinary/spike prices, all24
   horizons and both test weeks; report missingness and losing weeks. Preserve
   the protected demo interval. Then run the existing F4 savings/harm replay on
   identical appliance profiles/windows before considering adoption. Neither an
   expected baseline win nor bill savings is assumed.

The historical29 current-model origins are useful input-contract evidence and
are already inspected data, not a new untouched evaluation set. They do not meet
the proposed480-origin training floor, and no model-performance criterion is
completed by this preflight. Calendar/price pipeline and baseline-fallback work
remain delivered; real demand-aware fit and honest unseen-period comparison remain
the original CSDT4-35 gap.

## Checks

`PYTHONPATH=ml/src python -m pytest -q ml/tests/test_price_demand_readiness.py`
passed23 synthetic tests, including closed-form means/spreads, demand-sensitive
features, exact completed-price control, missing/future/conflicting inputs,
mapping, first-eligible deduplication, model/demo/stale exclusion and write-once
CLI output. These are contract checks, not measured accuracy. The existing
demand-candidate/pipeline/acceptance contract suite also passed. Commit `d18ec0a` was input-only; the subsequent
training guide explains the shared prediction/artifact/HTTP integration. No live
model pointer, schema or frontend was changed.

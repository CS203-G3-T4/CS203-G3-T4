# Controlled model improvement — 18 October 2026

Preserving the longer history improved the recent-data candidate, but **the
previous AI remains the best model by price MAE on the measured validation coverage**. The
strongest new candidate is frozen for future confirmation, without changing
serving, production eligibility or approval settings.

## What changed

`improve-history` compares six fixed schema2 candidates using three chronological
validation folds: October 10–12, October 13–14 and October 15–16, in Singapore time.
Baseline-input coverage was checked before fitting. These are previously examined
development dates; the results are exploratory validation, not untouched testing.

The controlled choices are:

- Long history plus recent data, or recent 28-day-window data alone.
- The existing 24-horizon squared-error HGB; smaller, more regularised
  absolute-error HGB; or ridge corrections to B2's same-half-hour-yesterday prices.
- Identical feature schema2: prices, calendar and versioned Singapore holidays,
  with demand explicitly missing when no origin-known forecast exists.

The long-history branch retains July 1, 2025–July 28, 2026 training targets and
preceding June warm-up from the existing official EMC final-price dataset. Its
18,840 historical training origins use explicit retrospective period-end input
availability assumptions. Those assumptions are not saved as receipt timestamps.
The recent branch uses collector provisional prices with genuine publication and
availability records. Their labels remain separate; final and provisional prices
are not relabelled or counted as duplicate observations for the same origin.

The July 29–September 22 protected test period, September 28 demo day, and the
reported October 17 test day are excluded from fitting and model selection.
Training targets are purged at cutoffs. Imputers and scalers fit training data only.
Residual models require a complete B2 anchor; missing anchors remain unavailable.
No price gaps, demand vintages or spike labels were manufactured.

The new read-only collector snapshot has 1,188 records, 1,150 unique half-hours
and 38 revisions through October 18, 15:30 SGT. SQLite integrity and pre-training
data checks passed. The full raw range still contains the earlier gaps and isolated
older observations; this is not continuous operational history.

## Measured results

Every row below uses the same **2,511 forecast pairs**, covering **111 origins and
196 distinct targets** across six days with common coverage. There are 8,064
scheduled pairs across the seven nominal validation days: common coverage is
**31.14%**. Missing baseline inputs, truth, stale observations and split-boundary
purging remain exclusions. Overlapping forecasts are not independent events.

| Method | Common-pair MAE, SGD/MWh |
| --- | ---: |
| Previous AI | **173.24** |
| Long history, existing HGB | **176.47** |
| Recent history, regularised robust HGB | 184.11 |
| Long history, B2 ridge correction | 193.92 |
| Long history, regularised robust HGB | 196.11 |
| B3 | 198.81 |
| Recent history, existing HGB | 204.85 |
| B1 | 220.97 |
| Recent history, B2 ridge correction | 240.46 |
| B2 | 270.52 |

Keeping long history lowered error **13.85%** relative to the otherwise matching
recent-only HGB. The strongest new candidate beat B3 by **11.23%**, but had
**1.87% higher error** than the previous AI. These percentages describe validation
MAE differences, not accuracy percentages or confidence estimates. No new
candidate consistently won every fold. No household-savings improvement follows
from these scores; the existing savings evidence remains unchanged.

The user's priority is spike prediction. The separate
[spike-focused grade](spike-grading-2026-10-18.md) finds stronger spike recall and
advance warning from the new long-history candidate, with more false alarms.
The MAE ranking above must not be interpreted as an overall spike-quality ranking.

Selection used pooled earlier validation only. The selected `LONG_DEFAULT`
candidate was then fitted with 18,840 historical origins plus 862 eligible recent
origins per horizon, preserving all protected exclusions. Its final 19,702-origin
fit differs from the fold models; **it has no independent accuracy score yet**.
It reloaded and produced 24 finite predictions using the shared feature code.

The selected artifact, selection and reference checksums are frozen for
**October 19–November 1**, ending **November 2, 00:00 SGT**. `confirm-improvement`
compares that frozen candidate, the previous AI and B1–B3 on identical eligible
future pairs, with separate coverage and missing-data reporting. It refuses an
early snapshot and never refits, reselects or promotes. Genuine origin-known
demand history and independent genuine-spike labels remain unavailable.

## Reproduce and verify

Activate the pinned Python environment described in the [runbook](runbook.md).
The following uses the immutable local datasets and trusted previous model;
those local data/model binaries are excluded from Git. Official source acquisition
and provenance are documented in [the acquisition report](historical-acquisition-2026-10-18.md).

```bash
export PYTHONPATH=ml/src
export OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 MKL_NUM_THREADS=1
# Research-only local audit spool; no live application API is needed.
export WATTLY_RUN_API_URL=
export WATTLY_ML_HOME="$PWD/.local/improvement-repeat-runtime"
timeout --signal=TERM --kill-after=15s 1800s python -m wattly_ml.cli improve-history \
  .local/improvement-2026-10-18-v1/collector \
  --historical .local/acceptance-2026-10-18/datasets/official-final-v1 \
  --reference-model .local/acceptance-2026-10-18/runtime/models/price-calendar-holiday-demand-v2-20261018 \
  --protocol docs/forecasting/evidence/2026-10-18-improvement/protocol-input.json \
  --output .local/improvement-repeat

# Recalculate saved errors and validate artifact integrity without fitting:
python -c 'from pathlib import Path; from wattly_ml.improvement import verify; print(verify(Path(".local/improvement-2026-10-18-v1/study")))'
```

After November 2, acquire a fresh immutable collector snapshot and prepare it
using the runbook, retaining the preceding warm-up history. Then run:

```bash
python -m wattly_ml.cli confirm-improvement /path/to/new/collector-dataset \
  --study .local/improvement-2026-10-18-v1/study \
  --output .local/improvement-future-confirmation
```

Outputs are immutable and reject reuse. Candidate binary and saved predictions
are under `.local/improvement-2026-10-18-v1/study/`. The durable local training-run
record reports SUCCESS, promotion RETAINED; audit API synchronization is pending.
The collector, live database, existing artifacts and serving pointer were unchanged.

Evidence: [frozen selection](evidence/2026-10-18-improvement/selection.json),
[fold comparisons](evidence/2026-10-18-improvement/summary.json),
[independent verification](evidence/2026-10-18-improvement/verification.json) and
[future plan](evidence/2026-10-18-improvement/prospective.json).

## Verification performed

The full Python suite passed **44 tests, zero failures/errors/skips**, in 543
seconds, with one upstream Starlette/AnyIO deprecation warning. Four new synthetic
regression tests cover residual-training equivalence and preprocessing, batch/single
prediction parity and missing anchors, protected periods and late revisions,
future confirmation without refitting, and artifact corruption rejection.

The Python wheel built successfully and includes the new module. Independent
recalculation reproduced all reported overall and per-horizon MAEs, canonical
actuals, pair counts, target alignment and split purging. Both frozen artifact
integrity and the previous model hashes passed checks. Real early confirmation
returned exit2 with the expected pending-until date and wrote no result directory.

Java, frontend, PostgreSQL and browser suites were not repeated: no application
runtime, Java, frontend or migration behavior changed in this step. Real future
confirmation and any resulting savings improvement remain unmeasured. See the
[test record](evidence/2026-10-18-improvement/tests.json) for exact scope.

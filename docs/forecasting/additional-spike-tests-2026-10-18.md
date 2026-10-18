# Additional spike testing — 18 October 2026

**The new candidate is more sensitive on the recent development data, but makes
substantially more false alarms. Historical results vary by period. These tests
do not justify production approval.** No model settings, spike thresholds,
baseline ranking or approval flags changed.

All results use the existing past-only, same-half-hour 28-day median/MAD rule
(minimum 14 observations, multiplier 3, spread floor 1 SGD/MWh). These are
rule-defined price anomalies; genuine-event labels are unavailable. Forecast
pairs overlap. The tables below use one prediction per distinct target at a
fixed three-hour lead, and separately count contiguous, fully observed events.
Missing event boundaries are censored. Advance warning means an alert's origin
precedes event onset; detecting a later part of an event can give less than three
hours' warning.

## Broader recent coverage

Regraded unchanged October 10–16 development predictions with fewer methods
required for common coverage. This is additional analysis of existing data,
not an independent blind test of the final October-fitted artifact.

The AI-only panel has **5,967 common labelled forecast pairs**. At three hours,
it contains **248 distinct targets: 48 positive and 200 negative**, with
**13 complete events and two censored runs**.

| Measure | Previous AI | New long-history candidate |
| --- | ---: | ---: |
| Positive targets caught | 9/48 | 22/48 |
| Target recall | 18.8% | 45.8% |
| False-alarm target half-hours | 13 | 54 |
| Alert precision | 40.9% | 28.9% |
| Events warned before onset | 4/13 | 8/13 |
| Events missed entirely | 9 | 5 |
| False-alarm runs | 5 | 14 |

For the new candidate, **54 of 76 predicted-positive targets were false alarms**.
Observed advance warning for both AIs was 120–180 minutes. Event-recall rates
remain unavailable below the existing 20-event support requirement. The earlier
6/6 finding was confined to a smaller cohort; it is not a reliable completeness
claim for the model.

A separate panel including B1/B2 has **5,415 common labelled pairs**, and at
three hours **225 targets (38 positive, 187 negative), 11 complete events and
two censored runs**:

| Method | Positive targets caught /38 | False-alarm targets /187 | Events warned /11 |
| --- | ---: | ---: | ---: |
| Previous AI | 9 | 13 | 4 |
| New candidate | 22 | 54 | 8 |
| B1 | 20 | 56 | 5 |
| B2 | 6 | 35 | 4 |

Compare methods only within a panel. B3 is absent from these panels and cannot
be ranked using its smaller-coverage MAE or spike metrics. On the original
all-method panel, leaving out each of six eligible origin days preserves the
new candidate's recall advantage and its higher false-positive count in all six
checks. These dependent checks are descriptive, not independent replications or
confidence intervals.

## Chronological historical strategy checks

Four additional fixed-setting research fits compare long history with the
previous 28 days, using identical existing HGB settings. Each fit ends before
its four-week evaluation window. Long training starts July 1, 2025: 335 days
before June's window and 365 days before July's. Recent fits use 28 days.
The windows are June 1–29 and July 1–29, 2026, with exclusive end dates.
July reproduces the already-used validation window; it is not newly blind.

These evaluate training strategies, **not the final October artifact predicting
its own past**. The July 29–September 22 protected test is excluded from these
fits and evaluations. Official final prices retain null publication/receipt
timestamps: this is a retrospective final-price study, not operational replay.

Pooled coverage is **63,360 common labelled forecast pairs**. At three hours:
**2,640 distinct targets, 559 positive and 2,081 negative, 125 complete events**.

| Strategy | Target recall | Alert precision | False-alarm targets | Events warned /125 |
| --- | ---: | ---: | ---: | ---: |
| Long history | 30.4% | 51.8% | 158 | 31 |
| Recent 28 days | 15.4% | 36.6% | 149 | 31 |
| B1 | 46.2% | 35.5% | 469 | 49 |
| B2 | 40.4% | 44.3% | 284 | 42 |
| B3 | 38.6% | 38.6% | 343 | 45 |

June has 54 events: long history warned 14, recent history five, with 52 versus
28 false-alarm targets. July has 71 events: long warned 17, recent 26, with
106 versus 121 false-alarm targets. Long history catches more positive half-hours
overall, but the advance-warning event counts tie. No strategy universally wins.
July's long-history MAE reproduces the original validation score,
77.49943854 SGD/MWh; this check was not used to select another model.

## Existing eight-week test, regraded separately

The unchanged old-AI July 29–September 22 test predictions contain **63,936 common
pairs**. At three hours: **2,664 targets (441 positive, 2,223 negative), 152
complete events and one censored run**. The new October candidate is absent.

| Method | Positive targets caught /441 | False-alarm targets | Alert precision | Events warned /152 |
| --- | ---: | ---: | ---: | ---: |
| Old AI | 75 | 130 | 36.6% | 36 |
| B1 | 210 | 612 | 25.5% | 64 |
| B2 | 116 | 257 | 31.1% | 52 |
| B3 | 67 | 151 | 30.7% | 38 |

This is existing retrospective final-price evidence, with no new fitting or
selection using the protected test. It cannot measure the new candidate's
unseen performance or be ranked against the different recent cohorts.

## Verification and reproduction

**51 Python tests passed in 412.39 seconds**, with one upstream Starlette/AnyIO
deprecation warning; no failures or skips. The new regression verifies explicit
retrospective evaluation without a reference AI, preserved null vintages,
negative predictions and default rejection of missing provenance. Existing
callers retain provisional, availability-aware defaults. The wheel built.

Independent recalculation checked confusion counts, distinct events and warning
timing, canonical truth, past-only thresholds, chronological training boundaries,
four artifact hashes, 24 finite predictions per artifact and scalar/batch parity.
The frozen October candidate and future plan remain unchanged. Early future
confirmation correctly exited 2 without creating an output directory.

Four local research audit records were written **after** completed fits; these
are not evidence of pre-fit scheduler auditing or API synchronization. They record
success with the champion retained. No live database/API or collector was changed.
Java, frontend, PostgreSQL and browser suites were not rerun for this offline
Python/evidence change.

From the repository root, use the pinned environment in the runbook. These
commands require the existing immutable datasets, saved predictions and study
under `.local`; Git evidence alone does not contain the raw datasets or models.
Choose a fresh output directory; the runners reject overwriting completed runs.

```bash
export PYTHONPATH=ml/src PYTHONDONTWRITEBYTECODE=1
export OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 MKL_NUM_THREADS=1
export WATTLY_SPIKE_TEST_HOME=.local/additional-spike-tests-rerun
mkdir "$WATTLY_SPIKE_TEST_HOME"
cp docs/forecasting/evidence/2026-10-18-additional-spike-tests/plan.json \
  "$WATTLY_SPIKE_TEST_HOME/plan.json"
PYTHON=/home/bryan/CS203_T4/wattly-f3-demo/tools/venv/bin/python
timeout --signal=TERM --kill-after=15s 1200s "$PYTHON" \
  docs/forecasting/evidence/2026-10-18-additional-spike-tests/run_saved.py
timeout --signal=TERM --kill-after=15s 1800s "$PYTHON" \
  docs/forecasting/evidence/2026-10-18-additional-spike-tests/run_history.py
timeout --signal=TERM --kill-after=15s 1200s "$PYTHON" \
  docs/forecasting/evidence/2026-10-18-additional-spike-tests/verify.py
timeout --signal=TERM --kill-after=15s 1200s "$PYTHON" \
  -m pytest ml/tests -q -p no:cacheprovider
```

Evidence: [recent panels and stability](evidence/2026-10-18-additional-spike-tests/recent-stability.json),
[historical summary](evidence/2026-10-18-additional-spike-tests/historical-summary.json),
[historical scoreboard](evidence/2026-10-18-additional-spike-tests/historical-scoreboard.csv),
[old-AI test](artifacts.md#retrieval),
[independent verification](evidence/2026-10-18-additional-spike-tests/verification.json),
[test record](evidence/2026-10-18-additional-spike-tests/tests.json).

The recommendation remains to keep the frozen candidate for its October 19–
November 1 confirmation, requiring a snapshot acquired on/after **November 2,
00:00 SGT**. See [confirmation commands](improvement-2026-10-18.md). Keep spike
misses, warning time and false alarms together when reviewing that outcome.
An agreed false-alarm budget and independent genuine-spike labels are still
missing. No team approval, production eligibility or household-savings benefit
is established by these tests.

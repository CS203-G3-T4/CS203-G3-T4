# Spike-focused forecasting grade — 18 October 2026

Follow-up: [additional testing](additional-spike-tests-2026-10-18.md) expands the
recent cohort to 13 complete events: the new candidate warned eight, the previous
AI four, with 54 versus 13 false-alarm targets. The six-event result below applies
only to its narrower coverage. The follow-up also reports historical checks and
the completed 51-test Python regression suite.

**The new long-history candidate catches more price-spike targets than the previous
AI, with more false alarms.** The previous conclusion that the old AI is best
applies to price MAE; it is not an overall grade for the user's spike-detection
priority. Carry the new candidate forward for the frozen future comparison with
missed events, warning time and false alarms reported prominently.

## What counts as a spike

This grade preserves the existing rule: compare a target half-hour with the same
half-hour over the preceding 28 days, using only information available at the
forecast origin. With at least 14 observations, the threshold is median plus
3 times max(1, 1.4826 times MAD), in SGD/MWh. A price strictly above that threshold
is positive. Ordinary evening prices are compared with their own usual evening
slot. No threshold, sensitivity parameter, model weight or approval flag changed.

These are **rule-defined unusual-price targets**, not independently labelled
genuine physical-cause spikes. Genuine-spike precision/recall remains unavailable.
The percentages below describe forecasting that fixed price-anomaly rule.

## Same-coverage forecast-pair results

All methods use the same **2,511 labelled forecast pairs**, covering 196 distinct
target half-hours: 42 positive and 154 negative. The same spike half-hour can be
forecast repeatedly from different origins; 693 positive forecast pairs are not
693 independent spikes. Common coverage is 31.14% of the 8,064 scheduled pairs
across the earlier October 10–16 validation windows. No protected test day was
used to choose models or thresholds for this report.

Recall means the fraction of actual positive forecasts caught. Precision means
the fraction of predicted alerts that were positive. False alarms below are
forecast-pair counts, not notifications sent to residents.

| Method | Spike recall | Alert precision | Missed positive pairs | False-alarm pairs |
| --- | ---: | ---: | ---: | ---: |
| Previous AI | 31.3% | 58.8% | 476 | 152 |
| New long-history HGB | **48.5%** | 55.8% | **357** | 266 |
| Long-history B2 correction | 39.4% | 43.3% | 420 | 358 |
| Long-history robust HGB | 10.2% | 50.0% | 622 | 71 |
| Recent-only HGB | 42.9% | 48.4% | 396 | 317 |
| Recent-only B2 correction | 44.0% | 41.4% | 388 | 431 |
| Recent-only robust HGB | 32.2% | 53.0% | 470 | 198 |
| B1 | **71.7%** | 50.2% | **196** | 494 |
| B2 | 18.8% | 24.3% | 563 | 404 |
| B3 | 17.9% | 52.1% | 569 | 114 |

The new candidate catches 119 additional positive forecast pairs and makes 114
additional false-alarm pairs versus the old AI. B1 has the highest recall here,
but also the most false alarms. Neither low MAE nor high recall alone establishes
the best resident alert experience. No false-alarm cost or budget has been agreed,
so this report does not invent an overall accuracy percentage or letter grade.

## Distinct targets and events at a fixed three-hour lead

To avoid treating overlapping origins as independent observations, this cohort
uses exactly one forecast per target at horizon6: **104 targets**, consisting of
26 positive and 78 negative half-hours. Every method uses those identical targets.

| Method | Positive targets caught /26 | False-alarm targets /78 | Events warned before start /6 | Events missed entirely |
| --- | ---: | ---: | ---: | ---: |
| Previous AI | 9 | 5 | 4 | 2 |
| New long-history HGB | **18** | 15 | **6** | **0** |
| B1 | 19 | 20 | 5 | 1 |
| B2 | 4 | 17 | 2 | 4 |
| B3 | 4 | 6 | 3 | 3 |

An event is a contiguous run of positive half-hours at this fixed lead, bracketed
by assessed negative half-hours. This cohort contains seven observed positive
runs; one has an unknown boundary and is censored, leaving six fully observed
events. An alert counts as advance warning only if its forecast origin precedes
the start of the event. Detecting an event after it starts is reported separately.
For the six events warned by the new model, observed advance warning was 120–180
minutes. Predicting a later portion of an event three hours ahead can give less
than three hours of warning before onset.

The new model's six warnings are promising, but **six events do not justify a
reliable 100% event-recall claim**. Event recall rates remain null until at least
20 fully observed events exist. Classification rates similarly require at least
20 distinct positive and negative targets. No confidence interval or physical-cause
spike accuracy is asserted.

The JSON also reports fixed 30-minute, six-hour and twelve-hour leads, plus the
usual next3h, 3–6h and 6–12h groups. Cohorts differ across leads; event counts must
not be added across them. If thresholds give different labels for the same target
at different origins, that ambiguity is exposed rather than silently collapsed.

## Implementation and future evaluation

`grade-spikes` validates saved flags against actual/predicted prices and the frozen
threshold, checks common truth/targets, rejects duplicate pairs, preserves missing
reference states, and produces JSON plus a chart/document-ready CSV. Only aligned
half-hour backtest origins are accepted for the fixed-lead evaluation.

Both `improve-history` and `confirm-improvement` now write `spikes/summary.json`
and `spikes/scoreboard.csv` alongside their existing accuracy reports. No training,
model reselection or promotion occurs during grading. The existing October 19–
November 1 candidate and future window remain unchanged; the additional
[spike-priority plan](evidence/2026-10-18-spike-grading/spike-priority-plan.json)
records the requested priority before those future prices exist.

```bash
# Activate the pinned environment from the runbook; run from the Git root.
export PYTHONPATH=ml/src
python -m wattly_ml.cli grade-spikes \
  .local/improvement-2026-10-18-v1/study/validation/predictions.jsonl \
  --output .local/new-spike-grade

# Recompute this report's confusion counts, past-only thresholds and unique events:
python docs/forecasting/evidence/2026-10-18-spike-grading/verify.py
```

See the [improvement report](improvement-2026-10-18.md) for future snapshot and
confirmation commands. Do not use the already-reported October 17 test outcomes
to retune sensitivity or select another winner.

Evidence: [full grade](evidence/2026-10-18-spike-grading/summary.json),
[CSV scoreboard](evidence/2026-10-18-spike-grading/scoreboard.csv),
[independent verification](evidence/2026-10-18-spike-grading/verification.json) and
[test record](evidence/2026-10-18-spike-grading/tests.json).

Verification: 13 targeted tests passed, covering hand-made confusion matrices,
overlapping forecasts, late warning, censored events, missing references, small
support, origin-dependent labels, time offsets, invalid flags and immutable
reports, plus the existing research workflow and spike-reference checks. The wheel
built with the new module. No Java, frontend, database, browser or full Python
regression rerun was needed for this offline grading change; the prior full Python
run had 44 passes. Existing serving, baseline ranking and approval gates remain
unchanged. Future spike performance and actual household savings remain unmeasured.

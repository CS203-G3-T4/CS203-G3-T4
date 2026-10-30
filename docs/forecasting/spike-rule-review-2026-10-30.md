# CSDT4-94 — spike-rule sensitivity and independent review

Prepared 30 October 2026. Owner: Bryan; reviewer: Jonathan. The owner selected human-reviewed unusual-price periods for validation, rather than requiring a published physical market event. This is a price-anomaly review, not proof of supply scarcity. No reviewer decision, human labels, model promotion or live threshold change is fabricated.

## Recommended decision

Retain the existing k=3 rule: compare each observed price with the preceding 28 daily values for the same half-hour; require at least 14 observations; typical=median; spread=max(1,1.4826*MAD); spike when price is strictly greater than typical+3*spread. This retains the owner-authorized setting while human validation remains outstanding. k=3 is not an established optimum or a calibrated confidence level. Keep ordinary time-of-day context, the existing appliance-overlap logic and S$0.05 minimum estimated saving. A current-price label is not an instruction to move an appliance and is not an advance warning.

The already applied K3_POOLED_WORST_WEEK_REVIEW_V1 candidate-review policy remains unchanged: aggregate false episodes and duration no worse than control; worst-week exposure-normalized rates capped by earlier control; no aggregate loss of advance detections; explicit lost/new/delayed detections; four complete prior weeks and at least 20 complete events. This is a proposed operational safeguard, not a verified user tolerance. The new human-label tool does not convert current-price labels into that different forecast/notification evidence contract.

## Recomputed historical sensitivity

A separate stdlib implementation recomputed the existing settings on all 4,368 half-hours from 1 April through 30 June 2026 (91 days, Singapore time), using preceding same-slot prices only. It reads the frozen official revised-final-price dataset, with SHA256 38ff304dd39ff80fb1b560bdeddb018e5fe8f52cd7b47084e65f894406b225a2. It reproduces every count in the previously committed sensitivity report exactly. No new model fitting, threshold search, protected-outcome evaluation or parameter selection occurred. Original publication vintages are unknown; these are retrospective final-price results.

| k | Flagged half-hours | Contiguous flagged runs | Fraction of assessed periods |
|---|---:|---:|---:|
| 2 | 884 | 216 | 20.2% |
| 3, current | 658 | 176 | 15.1% |
| 4 | 503 | 134 | 11.5% |

These are observed-price flags, not delivered resident notifications or independently confirmed events. Contiguous runs include runs touching cohort boundaries; they are not a count of fully bracketed genuine events. k=2 adds 226 flagged half-hours relative to k=3; k=4 removes 155. Without independent labels, neither change establishes better precision, recall or usefulness. The rates also show why a k=3 threshold must not be presented as a Gaussian 99.7% confidence claim.

Keep the counting units separate:

- The table above describes the current-price rule's flag burden on April–June prices. Genuine unusual-price precision/recall is currently unavailable.
- The saved solar-mean forecast's 55.4% precision / 40.7% recall measures repeated forecast-target pairs against the fixed statistical rule on a different June–July cohort. It does not validate the rule against independent truth or count independent onset events.
- The costly-warning study's 13/28 advance detections, 786 false-warning origins and 199 false episodes uses another label/cohort/policy. These are not 199 delivered household notifications and must not be mixed with 176 observed-rule runs.

## Independent review workbook

`scripts/review-spike-rule.py build` produces a standalone workbook, a blind annotation cohort, a separate rule audit and an awaiting-labels status file. Generated price data stay outside Git. The workbook deliberately contains no forecasts, k thresholds or rule decisions. It covers the whole cohort, not only flagged or high-price examples. Each period begins unreviewed. Reviewers can inspect its actual price and the preceding 28 same-slot values, record SPIKE / NORMAL / UNCERTAIN with a rationale, and bulk-mark remaining periods in a day normal only after entering a review reason. Uncertain and unreviewed are never silently treated as negative truth.

The workbook stores progress in that browser and exports a JSON label file. The reviewer records a name, definition and a declaration that rule/model decisions were not used. This is self-attestation, not identity verification or independent confirmation that a person followed the process. Use a fresh review of the workbook before opening the separate rule audit or the unblinded illustrative appendix. Agent/browser tests created synthetic labels only, not human labels for actual prices.

The scorer requires the exact cohort identity and unchanged audit digest, a written label definition, named reviewer, reasons, aware review/decision times and a blind-independence declaration. It rejects duplicates, unknown cases, future reviews and incompatible causal-label claims. Partial reviews report coverage and labelled-subset counts only; whole-cohort metrics remain null. Complete human-labelled reviews report TP/FP/FN/TN and precision/recall per fixed k; undefined denominators remain null. Complete review still cannot assert physical scarcity, team approval or model promotion. Small labelled samples are exploratory and must not be extrapolated to the full cohort.

```bash
export PYTHONPATH=ml/src
python scripts/review-spike-rule.py build /private/prices.jsonl \
  --start 2026-04-01T00:00:00+08:00 --end 2026-07-01T00:00:00+08:00 \
  --output /private/spike-review-new
# Open review.html, review prices without consulting rule-audit.json, then export labels.
python scripts/review-spike-rule.py score /private/spike-review-new/rule-audit.json \
  /private/spike-review-labels.json --decision-at ACTUAL_AWARE_SCORING_TIME \
  --output /private/spike-review-score-new.json
```

Files are write-once. Existing reports/labels are never overwritten, and the tool cannot train, tune a model, invoke an app endpoint, change configuration, send notifications or edit Jira. The local served workbook is an operator preview; its generated data and browser storage are not the final submitted labels.

## Unblinded examples for discussion after labelling

Do not use these algorithm-selected examples as independent ground truth. Their purpose is to explain why flag counts alone cannot choose k.

| Period SGT | Actual SGD/MWh | Past same-slot median | What the rule does |
|---|---:|---:|---|
| 1 Apr 00:00 | 224.55 | 168.56 | k=2 flags it; k=3 does not (threshold 227.25) |
| 1 Apr 01:00 | 215.12 | 168.61 | k=3 flags it; k=4 does not |
| 18 May 09:30 | 1,208.20 | 174.62 | All three settings flag it |
| 30 Apr 20:00 | 262.61 | 206.70 | None flags it; evening context matters |
| 12 Apr 12:00 | 168.65 | 139.80 | k=3 flags it despite a modest absolute price |

For the last example, the difference from the historical median is about S$0.0289 per kWh. This is an illustrative price difference, not an achievable saving or an appliance recommendation; the alternative run's actual cost and constraints still matter. A statistical price flag can have little household impact, which is why changing k alone is not a savings algorithm.

## Verification and remaining acceptance

46 focused checks pass: 22 new audit/label/CLI cases, the existing 23 warning-budget cases and the ordinary-evening-peak regression. Tests cover hand-calculated strict thresholds, missing reference exclusion, withholding unsupported metrics, partial/uncertain coverage, clock/order checks, changed/different cohorts, duplicate labels, reasons, identity attestation, immutable files and CLI round-trip. Browser checks cover 91-day navigation, all 48 daily rows, desktop/mobile overflow, persistence, export and independence requirements. All browser-created annotations were synthetic; actual human labels remain zero.

CSDT4-94 should remain In Progress: the computational report and review tooling are ready, but human labels and Jonathan/team agreement are still outstanding. For acceptance, the team must (1) complete an independent price-label review and explicitly accept its coverage/limitations, (2) review measured precision/recall on that labelled coverage without calling rule self-labels genuine validation, and (3) record its sensitivity choice and alert-budget interpretation. The owner already authorized retaining k=3; this does not substitute for the named reviewer's decision. If the team accepts a smaller labelled subset, keep its metrics explicitly scoped to that subset.

The [compact evidence](spike-rule-review-evidence-2026-10-30.json) records reproducibility, zero human labels, tests and pending sign-off. The active solar model, its weights, live trial and weekly retraining state are unchanged.

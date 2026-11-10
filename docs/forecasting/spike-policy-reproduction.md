# Reproduce the fixed spike-policy validation offline

The portable grader is [spike_policy_validation.py](../../ml/src/wattly_ml/spike_policy_validation.py).
It preserves the grading core from the frozen 1 November operator evaluator,
adds an explicit input contract and refuses malformed, leaking or duplicate
evidence. It never connects to PostgreSQL, reads environment credentials,
fetches a feed, trains a model, changes runtime settings or sends a warning.
Python 3.12 and the standard library are sufficient for this command; the normal
package's ML dependencies are not imported. Existing project tests use pytest.

## Public synthetic example

From the repository root, choose a new output filename:

```bash
PYTHONPATH=ml/src python3 -m wattly_ml.spike_policy_validation \
  --snapshot ml/tests/fixtures/spike-policy-snapshot.synthetic.json \
  --protocol ml/tests/fixtures/spike-policy-protocol.synthetic.json \
  --mode preliminary --as-of 2026-01-02T03:10:00Z \
  --output /tmp/wattly-spike-policy-example-new.json
```

The examples are explicitly **synthetic**, with fake model/trial/run identifiers.
They demonstrate one half-hour spike, an AI avoidance plan and cost arithmetic;
they are not source data, calibration, human labels or an accuracy result.
Expected output: AI TP=1/FP=0/FN=0/TN=2 on three pairs; B3 misses that proxy spike;
one joint-useful candidate episode; insufficient support and an undetermined
two-false-in-ten target. `productionEligible` and `automaticPromotion` remain false.

Run the focused synthetic checks with:

```bash
python -m pytest -q ml/tests/test_spike_policy_validation.py
```

## Reproduce the reported private preliminary snapshot

Ask the owner for the **authorised private export** and frozen protocol through
the team's private records. This tool makes the calculation reviewable; it does
not authorise redistribution or make restricted inputs public. Do not export
credentials or entire household/account tables. Pseudonymous identifiers only
need to preserve equality and first-plan grouping within the batch.

The original snapshot SHA256 is
`84b38fb1aae1a095a778a84464ff6813c12dfdca602b03308493df612565f4e6`;
the frozen protocol SHA256 is
`debba9d56b0501885d67241a4c3329b37776925d04eebdf13355c04ba40384bc`.
For those exact, unmodified bytes, use:

```bash
PYTHONPATH=ml/src python3 -m wattly_ml.spike_policy_validation \
  --snapshot /private/snapshot.json --protocol /private/protocol.json \
  --mode preliminary --as-of 2026-11-01T06:41:44.828522+08:00 \
  --output /private/reproduced-preliminary-new.json
```

Local replay of the same hashes matches **all 27 grading/result fields** in the
original operator result, including 198 completed AI/B3 pairs over 11 targets,
12 separately assessed normal periods, 12 reference parity checks, zero new
appliance plans and an undetermined personal-warning target. The source's own
runtime-guard receipt is separate: this offline command does not repeat live
service/model checks or prove the export's read-only/authenticity claims.
Integrity hashes identify bytes; they do not establish human truth or source
authenticity. `inputAuthenticityVerified=false` and `runtimeGuardsExecuted=false`
make these limits explicit. Inputs and existing outputs are never overwritten.

## Snapshot and protocol contract, version 1

The example JSON files are the complete field-level template. Required groups:

| Object | Required evidence |
|---|---|
| Snapshot | `snapshotAt` (aware timestamp), source `readOnly="on"` declaration, `trials`, `pairs`, `opportunities`, `history`; mark synthetic examples `synthetic=true` |
| Trial | A unique matching `id` and `model_version` for the protocol |
| Price pair | `run_id`, integer `horizon` 1–24, `as_of`, `captured_at`, `model_version`, aligned `target_period`, finite `ai`/`b3`, nullable `threshold`/`actual`, `evaluated_at` when scored |
| First plan | Unique `id` and household/appliance/usual-start tuple; matching `trial_id`/`run_id`/`issued_at`; `usual_start`, `ai_start`, `b3_start`, integer `run_minutes`; frozen `prediction` and nullable `outcome`/`evaluated_at` |
| Prediction | Numeric `AI`, boolean `aiWarning`, issue-time `aiRange` with boolean `available`, integer `completedDays`, and ordered bounds when available |
| Outcome | Saved `AI` `usualCostSgd`, `chosenCostSgd`, `savingSgd` with consistent subtraction; scoring time after all three runs finish. It remains trusted saved outcome evidence, not a new independent meter/cost audit |
| History | Unique revision `id`, source, aligned period, known publication/receipt times, finite USEP and FINAL/PROVISIONAL status; later-than-snapshot revisions and conflicting equally available prices are refused |
| Protocol | `schemaVersion=1`, exact policy ID/candidate/support constants, pinned trial/model, `fixedAt`, declared preliminary limitations and a fixed seven-day prospective window with one score after 13 further hours |

All timestamps must include an offset. Unknown/missing truth stays pending;
uncertain human labels are not inferred from this export. Duplicate JSON keys,
run/horizon rows, first plans, changed model/policy, non-finite prices, unaligned
targets, future receipts/outcomes and invalid saved arithmetic are refused.
Each input file is limited to 256 MiB. Operator-only paths/hashes in a legacy
protocol are metadata: the grader does not execute them or import code from them.

`--as-of` is explicit and must be no earlier than the snapshot. For prospective
mode, the snapshot must also be at or after the frozen `scoreAfter`. There is no
system-clock fallback or early-peeking mode. This is deterministic offline
validation, not independent authentication of a claimed timestamp.

## Frozen forward study and limits

The [policy](csdt4-33-policy-2026-11-01.md) and existing single 8 November 20:00 SGT
follow-up remain unchanged. The original frozen operator exporter/evaluator and
source guards stay in place; this portable adapter does not replace them mid-test.
When authorised inputs are available after the outcome window, use the same
command with `--mode prospective`, a matching aware `--as-of`, and a new output.

The report separates dependent run-target pairs, earliest-issued fixed-horizon
targets, observed statistical flags and deduplicated counterfactual initial
household episodes. It does not grade later notification updates, actual delivery
or meter adherence. Savings-range inputs are trusted issue-time app evidence,
not recalibrated from future outcomes. Missing calibration and zero warnings do
not establish reliability. Human genuine-spike labels and team approval remain
separate; the minimum support counts are not a population-confidence guarantee.
No model or live setting is changed, even when an observed target is met.

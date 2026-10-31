> 1 November follow-up: the [paired price-only savings and safety study](price-only-safety-2026-11-01.md) now provides the current-recipe results. The activation-time claims below are historical; no later policy has been adopted.

# Local price-only AI and separate comparison — 31 October 2026

At the owner's request, local inference now uses
`price-only-183d-20261031-local-v1` rather than the solar-assisted bundle. The owner
also requested a separate price-only comparison, preserving the old solar trial.
The EMC solar/demand helper remains running for the separate demand experiment;
no instruction to stop it was given after its impact was explained. The live
price-only predictor has no dependency on that helper's solar or demand values.

## Model and input contract

The exact trusted `PRICE_183D.joblib` final research artifact has SHA256
`42c873964c9489dd70bae1d037ef3982e4909f71b268c8a7aa16f7e47b889107`.
Its 24 fitted gradient-boosted pipelines used 183 days / 8,760 origins before
29 July 2026. No fitting, threshold search or new outcome scoring was performed.
The original artifact and solar model bytes are unchanged.

The packaged models SHA256 is
`023fb405d78ac19eb046b3a17ac11e70ef9736eca761c46456d6128428578ce9`;
manifest SHA256 is
`47d8c29c5e93f5d75d5f668c4c88be25cfdff023e5fc51cd067f783514e7c2da`.
The new manifest records packaging time conservatively as availability, not a
new training event. Serving remains hash-pinned, loopback-only, explicitly
experimental and `productionEligible=false`; no approved pointer was created.

The 19-column schema-2 recipe uses completed-period prices, calendar and holidays.
Expected-demand columns stay missing/zero exactly as fitted. The explicit
`COMPLETED_PRICE_CALENDAR_HOLIDAY_NO_AUXILIARY_V1` policy strips current/future prices
and auxiliary attributes before inference. Missing the latest completed price
fails closed to the normal baseline path. Current observed prices still appear
immediately in the app; the model's numerical inputs preserve training parity.
No solar file or expected-demand forecast is required. Existing schema-2 models
without this named policy retain their existing behavior.

Package a trusted source with `scripts/package-price-research-model.py`, pin its
source checksum, provide the existing baseline score report and choose a new
immutable version. Only the experimental opt-in can load this bundle. The
package helper does not fit, approve or activate it.

## What the error evidence does and does not show

On the same 59,904 already examined June–July development forecast pairs:

| Forecast | All-price MAE (cents/kWh) | Ordinary MAE | Spike MAE |
|---|---:|---:|---:|
| PRICE_183D | 5.2851 | 3.0734 | 13.5669 |
| SOLAR_183D | 5.2023 | 3.0096 | 13.4128 |
| B3 | 5.9333 | 3.8502 | 13.7335 |

Solar reduced overall MAE about 1.6%; price-only error was about 10.9% lower than
B3. These are rolling weekly research fits against revised final prices, not
proof that this single July bundle achieves the same live performance. The solar
recipe's S$37.2051 result is **not** attributed to price-only AI. There is no new
paired price-only appliance-savings superiority claim.

## Separate prospective comparison

`price-only-live-20261031-v1` starts **31 October 2026 16:48:09 SGT** and ends
**28 November 2026 16:48:09 SGT**. It pins the new version/hash. The first confirmed
new-model forecast was issued at 16:49:41 SGT; activation checks completed at
16:49:42. The initial setup gap is retained, not hidden by moving the start.

The original `solar-live-20261030-v1` metadata, model pin, policy, issued plans and
records are retained. New price-only issues are not pooled into it. Its remaining
pending outcomes are not claimed complete; the active collector/evaluator now
addresses the new trial, so any finalization of the old trial is separate work.

The new comparison uses identical AI/B3 target prices and appliance constraints,
first eligible plans, the existing S$0.05 minimum, top-three limit, 28-day duration,
real provisional receipt truth and frequency weighting. Accepted appliance start
times are not changed. Model-specific savings ranges collect new evidence and
remain unavailable until the completed-day requirement is met. No false confidence
range is copied from the solar model.

At successful activation there was one eligible issue, 24 paired price forecasts
and nine frozen appliance-run opportunities. These are collection counts, not
scored savings or independent customers. The earlier solar trial was descriptive
without a preregistered binary superiority margin; this new trial likewise reports
paired savings, harmful moves/losses, weekly variability and coverage without
automatic promotion. Insufficient coverage is inconclusive.

## Activation boundary and recovery

Only inference source/configuration and the model/trial selection changed. The
running Spring JAR and applied migration history are unchanged. The Java review
repairs and coordinated replay V23–V25 / trial V26 / diagnostics V27 still require
the reviewed merge and schema rollout; they were not silently deployed here.

Private configuration and a database dump were backed up before switching. The
first attempt restored configuration when an owner-local refresh helper failed to
compile; no records were deleted and the newly created trial was retained. The
corrected helper was compiled before the successful retry. It ran the unchanged
Spring JAR's real forecast/background recommendation services in a temporary
loopback context with external collection disabled, then closed that context.
This avoided inventing a market update or opening a public force-forecast API.

Rollback uses the private original environment and service restart, followed by
readiness and saved-forecast identity checks. Do not delete forecasts, reset the
trial or edit applied migration history. The old immutable model remains available.
No automatic retraining or timer activation occurred.

## Verification

- 181 Python tests pass; 40 focused price-only/solar/experimental checks pass.
- 7,296 feature scalars exactly match the cached CONTROL inputs at 16 distributed
  development origins. 384 saved weekly predictions reproduce with zero error;
  384 packaged final-model predictions match the original fitted pipelines.
- Real loopback HTTP with 1,342 actually receipt-stamped price rows and an absent
  solar path produces 24 predictions identical to offline inference.
- Real local Spring forecasts identify the price-only version, are actionable and
  carry no solar flags. A resident reads saved F3 recommendations with demo=false.
  Desktop/mobile browser checks pass with no page errors or page overflow.
- Old trial metadata, old solar model bytes, Spring JAR and schema history match
  their pre-switch copies. Actual screenshots, raw inputs and backups remain private.

[Activation](evidence/2026-10-31-price-only/activation.json),
[parity](evidence/2026-10-31-price-only/parity.json),
[HTTP](evidence/2026-10-31-price-only/canary.json),
[browser](evidence/2026-10-31-price-only/browser.json).

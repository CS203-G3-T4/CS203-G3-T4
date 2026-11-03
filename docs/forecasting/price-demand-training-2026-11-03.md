# CSDT4-35: prepared price-plus-demand trainer without solar

The [export, F4 replay and isolated rehearsal guide](price-demand-flow-2026-11-03.md)
now supplies the repeatable dataset and end-user comparison steps. Real-study
registration, ongoing capture and real measured results remain outstanding.

The no-solar training and inference code is prepared. **No real demand model has
been fitted, adopted or deployed. Task35 remains incomplete on real fitting and
unseen-period evaluation.** No Jira, live model/trial, collector, timer or serving
configuration was changed. The earlier solar experiment and reserved spike
evaluation remain separate and unchanged.

## Shared recipe and inference

The new policy is `COMPLETED_PRICE_CALENDAR_HOLIDAY_AS_ISSUED_DEMAND_V1`, feature
schema2, with `expectedDemandPolicy=MANDATORY_ALL_24_AS_ISSUED` and
`researchRecipe=PRICE_DEMAND_COMPLETED_V1`. Training input preparation and the
existing backtest/HTTP prediction path share `completed_price_demand_history`.
It keeps the current price-only recipe's completed price history, calendar and
holiday inputs, but retains all24 target-matched demand forecasts known by the
original issue time. It has no solar input. Missing, future, negative, non-finite
or conflicting demand fails; it never becomes zero or a silently missing feature.

Bundle loading validates the policy, schema and mandatory-demand contract and
requires `productionEligible=false`. HTTP inference requires the existing
explicit experimental opt-in, pinned manifest and trusted artifact directory.
Responses disclose completed-period inputs, as-issued demand and EXPERIMENTAL_AI.
Neither a missing solar file nor an existing solar snapshot affects this recipe.
An invalid/missing target fails inference, allowing the existing Java baseline
fallback; this change does not activate a new server or replace its model.

## Bounded experiment runner

`python -m wattly_ml.price_demand_training` provides explicit `fit` and `evaluate`
commands. It does not collect data, register a plan, start a timer, modify a model
pointer or promote a model. All business time is supplied explicitly with
`--as-of`; no new system-time call was added to the application code.

The [draft template](price-demand-protocol.draft.json) is intentionally rejected
by the runner. Before using real data, agree the ongoing authorised demand
source/capture arrangement, then register a separate plan with fixed dates and
the exact frozen price-only manifest hash. Registration must precede the training
cohort. Real origins cannot start before8November20:00 Singapore, and the reserved
score must actually have completed. The registered training window must span at
least14days, followed by at least one day for the explicit fit, then exactly14
untouched test days. Require480 distinct complete origins per split after input,
receipt and split-boundary exclusions. These floors do not guarantee enough
independent spike events. Dates remain unset while capture is undecided.

The gate also keeps the protected demo interval out of the new real recipe's
maximum29-day input history: a real cohort beginning after8November cannot reach
the protected period ending29September. Existing model/trial dates are not
rewritten to create this study.

The runner uses original, fresh LIVE snapshots from the pinned current-model
version. It selects the first eligible original issue in each half-hour and
never reconstructs later demand vintages. It purges any origin whose last target
half-hour has not ended by the split cutoff and requires every actual to have
been received by that cutoff. Truth is provisional, with original publication,
receipt and verified-period evidence. Later repairs and unknown truth do not
complete earlier training examples. Timing gates run before the CLI opens the
input/outcome exports; training filters receipt metadata before inspecting
outcome values. Held-out input values are not used by the fit.

The single fit creates24 horizon-specific HGB regressors for each of:

- `PRICE_DEMAND`: completed prices plus mandatory as-issued demand.
- `PRICE_REFIT`: the same completed prices/examples/outcomes with demand omitted,
  matching the existing price-only recipe.

Both use the existing fixed40-iteration/15-leaf/L2=1/seed42 recipe, without early
stopping or a parameter search. Manifests record the same training-input digest.
The frozen current model and B1/B2/B3 are additional evaluation controls. The
simple baselines retain their **original available issue-time prices**, including
the current quote where their existing formula uses it. They are not weakened
to the AI's completed-price restriction. Every method is scored on identical
eligible issue/target/outcome pairs.

Use one retained registry root per experiment. The study directory is the
canonical protocol SHA256 under that root. A directory is claimed atomically
before fitting; FAILED and SUCCESS attempts remain retained and neither is
automatically retried. Do not evade the one-fit rule with a different root or a
changed protocol. A failure requires an explicit reviewed decision.

The end-of-test evaluation verifies the unchanged protocol, successful fit,
pre-test artifact timing, fitted-manifest hashes and frozen-model pin. It claims
one evaluation attempt and writes an immutable `evaluation` report directory.
Failures are retained. It reports MAE/RMSE overall, ordinary/spike prices, each
horizon and each test week, plus fixed-lead events and false-issue burden.
Unassessable reference periods remain unavailable; zero warnings do not imply
perfect precision. All six methods have the same common pairs. Overlapping
horizons remain dependent, and two weeks cannot prove seasonal consistency.

## Operator commands, after real registration

Use authorised private exports in the original shape documented by the
[input preflight](price-demand-readiness-2026-11-03.md). Price truth rows retain
`periodStart`, `sourceUpdatedAt`, `availableAt`, `priceStatus`, `timeMapping`,
`source`, and `usep`. Keep inputs, registry, model weights and generated reports
outside Git. Do not export reserved holdout data early just to run these commands.

```sh
PYTHONPATH=ml/src python -m wattly_ml.price_demand_training fit \
  --issues /private/training-issues.jsonl --prices /private/received-training-prices.jsonl \
  --protocol /private/registered-price-demand-protocol.json \
  --as-of ACTUAL_TIMESTAMP_WITH_OFFSET --root /private/price-demand-registry \
  --frozen-model /private/models/price-only-183d-20261031-local-v1

PYTHONPATH=ml/src python -m wattly_ml.price_demand_training evaluate \
  --issues /private/heldout-original-issues.jsonl --prices /private/test-end-price-vintages.jsonl \
  --protocol /private/registered-price-demand-protocol.json \
  --as-of ACTUAL_TIMESTAMP_WITH_OFFSET \
  --experiment /private/price-demand-registry/PROTOCOL_SHA256 \
  --frozen-model /private/models/price-only-183d-20261031-local-v1
```

The explicit caller-supplied clock and retained local files are operator evidence,
not cryptographic proof of independently witnessed issue/registration times.
Original production receipts, controlled storage and human review remain necessary.

No automatic acceptance or adoption follows a positive report. The report keeps
`savingsValidation=PENDING_REAL_F4_REPLAY`: pass the identical predictions through
the existing F4 appliance replay, retaining harmful moves and losing weeks, before
considering a later model/trial release. If the bounded candidate fails, retain
the current model and report the failure without another search.

## Verification evidence

The focused suite passed71 tests. New synthetic checks fit and load all24 models
for each refit and evaluate48 test origins on1152 common pairs per method across
all six methods. Synthetic HTTP tests demonstrate demand-sensitive prediction,
ignored current-period price, no solar-file dependency, missing-demand rejection,
experimental-only serving, denied promotion, timing/support gates, retained failed
attempts, immutable evaluation and model-pin checks. Deliberately unreadable
future outcome/input values do not affect training. These are plumbing checks,
not evidence of real accuracy improvement, baseline superiority or bill savings.

An already inspected original pre-reservation input also reproduced all24 frozen
price-only predictions exactly under the new code (maximum difference0). No raw
input or outcome from the reserved1–8November cohort was inspected. No real
candidate was trained. Full-suite/CI results are recorded in the PR validation.

There is no schema migration, new dependency or UI change. Recovery before any
later deployment is an ordinary source/build rollback; current serving remains
on its original artifact. Any eventual experimental serving must explicitly
select and pin the new artifact, and any model change during a frozen trial needs
its own separate comparison rather than rewriting the existing trial.

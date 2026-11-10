# Demand experiment: export, savings replay and isolated rehearsal

This completes the tooling between the prepared trainer and F4. It does not
register a real study, fetch an upstream feed, fit a real demand model, change
live serving, start collection/retraining or edit Jira. The draft stays DRAFT.
The original solar experiment and reserved spike cohort are unchanged.

## Read-only original-data exports

`wattly_ml.price_demand_export` connects through a **privately configured DSN
environment variable**. Its CLI accepts only the variable's name. It uses the
existing PostgreSQL adapter's read-only, repeatable-read transaction; connection
errors and persisted failure reasons exclude DSNs, credentials and input contents.
The exporter makes no database writes and creates a new private output directory.
Failed exports stay retained and the same directory is not automatically retried.

The registered protocol and timing gate are checked **before connection**. Train
export is allowed only between training cutoff and test start. Test export waits
until the complete test end. SQL limits price vintages by original publication,
receipt and completed-period cutoff. It preserves all eligible truth revisions;
late revisions cannot replace earlier-cutoff truth. It never reconstructs demand
from the current demand table: all inputs come from original saved LIVE snapshots.

The first eligible original issue per half-hour is exported from the pinned source
model, with invalid/repeated source counts retained. Its market input fields and
timestamps are preserved. Replay/demo, stale and unavailable rows do not become
examples. Only price/demand provenance is projected; household, account and solar
payloads are excluded. A source-snapshot hash binds the original record. There are
explicit row limits, SQL timeouts and a private directory/file mode.

The manifest records protocol hash, split/window/cutoff, source exclusions,
actual-receipt-complete origins, support floor and hashes for both JSONL files.
Insufficient support is reported explicitly; an export does not start training.
The trainer's `--dataset` option checks those bindings and file hashes, while
retaining its independent input, outcome and timing guards. Older manual-file
arguments remain supported. Do not mix them with `--dataset`.

After real registration and authorised ongoing capture, use the intended host's
private database variable. Never place its value in source, a command argument,
PR text or a shared log:

```sh
PYTHONPATH=ml/src python -m wattly_ml.price_demand_export \
  --protocol /private/registered-protocol.json --split train \
  --as-of ACTUAL_TIMESTAMP_WITH_OFFSET --output /private/new-train-export \
  --dsn-environment WATTLY_EXPERIMENT_DATABASE_URL

PYTHONPATH=ml/src python -m wattly_ml.price_demand_training fit \
  --dataset /private/new-train-export --protocol /private/registered-protocol.json \
  --as-of ACTUAL_TIMESTAMP_WITH_OFFSET --root /private/registry \
  --frozen-model /private/models/price-only-183d-20261031-local-v1
```

Only after test end, repeat export with `--split test` and a new directory, then
run the trainer's `evaluate --dataset /private/new-test-export --experiment
/private/registry/PROTOCOL_SHA256`, supplying the same protocol, frozen model and
explicit time. Keep generated data, model weights and reports outside Git.

## F4 savings and harm comparison

`wattly_ml.price_demand_savings` consumes a successful completed price evaluation
and its original protocol. Freeze the **same household/appliance profiles and
policy before registering the study**, then put their exact file SHA256 in
`savingsProfileSha256`. The adapter refuses a changed or unpinned profile/policy.
This pin is needed for this fixed savings comparison; it does not retroactively
add household savings to task35's original price-evaluation acceptance criteria.

The private profile JSON contains `syntheticTestOnly`, `minimumSavingSgd`,
`maxActive`, and `households`. Each household has `id`, `exposedToWholesalePrice`
and `appliances`; each appliance has `id`, `powerKw`, `runMinutes`, `earliestStart`,
`mustFinishBy`, `usualStart`, `runsPerWeek`, `enabled` and optional `flexibility`.
Use authorised, frozen profiles; synthetic and real studies cannot be mixed.
The rehearsal uses S$0.05 and3, matching the current recommendation policy.

The adapter compiles the **actual checkout's** `CheapestStartCalculator`,
`PriceForecast` and F2 `TimeWindow` with a small offline Java bridge. It does not
copy the optimiser or substitute a Python planner. Java21+ and its compiler are
required; paths can be supplied explicitly. The bridge chooses starts using only
predictions and their saved spike flags, then calculates actual costs afterward.

All six methods require identical complete origin/target/actual pairs. Duplicate,
missing, conflicting or malformed records fail rather than dropping a losing
method. Frozen profiles are the same for every method. Fixed-rate households are
not shifted. Rankings use predicted saving, earlier start and appliance ID, with
the frozen per-refresh cap. A first suggested move is assumed accepted and locks
that household/appliance/usual-run case across later cohort refreshes. Runs are
deduplicated and weighted consistently by `runsPerWeek/7`. Unknown spike flags
permit ordinary price planning but remain unknown for warning assessment.

The immutable report includes common case counts, moves, simulated wholesale
savings, harmful moves/loss, worst run loss and both weeks' results/differences
against B3 for every method. Losing weeks remain visible. Zero common cases are
explicitly insufficient support. Sources, profiles and prediction files are
hashed before and after replay. The original price report stays immutable; the
linked replay summary records completion of this separate stage.

```sh
PYTHONPATH=ml/src python -m wattly_ml.price_demand_savings \
  --evaluation /private/registry/PROTOCOL_SHA256/evaluation \
  --profiles /private/frozen-household-profiles.json --source-root /path/to/checkout \
  --as-of ACTUAL_TIMESTAMP_WITH_OFFSET --output /private/new-savings-report
```

These are **counterfactual modelled wholesale savings**, not measured household
bills or proof of actual user adoption. The replay assumes the fixed profiles and
first-acceptance policy; it does not reproduce snoozing, actual notifications or
every live five-minute refresh. Price-linked bill components/GST are handled by
the real app's realised-savings flow, separately tested in the rehearsal. This
tool does not mutate accepted plans, change live thresholds or authorise adoption.

## Complete isolated application rehearsal

From a developer checkout with Python/test dependencies, Java21+, Maven and
PostgreSQL17 tools, run `scripts/rehearse-price-demand.py` with `--private-root`,
`--pg-bin`, and optional `--java`/`--javac` paths. A bundled PostgreSQL build may
need its library directory supplied through the caller's normal environment.

The script creates its **own** private database and random loopback ports. It
never connects to the live app/database, upstream collector or real model home.
The test-only `DemandRehearsalApplication` injects a fixed business clock and runs
the ordinary forecast job at startup. It is compiled only under test sources;
there is no new production endpoint or production clock override.

The rehearsal performs:

1. Normal Spring migrations, fixture login/CSRF, and a real F2 appliance creation.
2. Profile/policy freezing before synthetic train/test exports and fitting.
3. Read-only train/test exports, one24-model candidate/control fit and one common
   held-out evaluation, all clearly synthetic and privately retained.
4. A real loopback Python server, the actual Java client and saved24-point AI
   forecast, explicitly marked experimental without a solar file.
5. F4 recommendations using **`recommendation.forecast-source=f3`**, then resident
   acceptance and a pending savings record. The demo default is not changed.
6. F4 replay on the exact same common household cases for all six methods.
7. Missing mandatory demand causing the real Java job to retain forecasts using
   B3, while the already accepted appliance plan remains unchanged.
8. Advancing only the test business clock and receiving actual prices, then the
   actual realised-savings API producing a completed result.

All three private services are stopped in cleanup, and a private `rehearsal.json`
records the checks and evidence location. Generated datasets, model weights,
profiles, database files and logs are never committed. The rehearsal is a synthetic
integration proof, not a real accuracy/savings result or independent human review.

Focused tests also verify repeatable-read isolation against a concurrent revision,
preservation of receipt instants across timezone representations, invalid-first
capture exclusion, credential redaction, immutable exports, profile/policy pins,
common-pair rejection, run deduplication, harmful moves, and the fact that changing
actual prices changes savings without changing selected plans. CI supplies Java21
to the Python job so these tests execute the real Java calculator.

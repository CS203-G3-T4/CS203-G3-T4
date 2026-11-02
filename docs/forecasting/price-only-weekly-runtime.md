# Price-only weekly runtime integration [CSDT4-59]

This completes the **inactive integration preparation**, on the same isolated
branch as the [price-only rehearsal](price-only-weekly-preparation.md). No service
is installed/enabled, no live application/collector/model/trial is changed, and
no candidate is automatically adopted. Tests use synthetic inputs and disposable
services/databases; passing them is not evidence of improved price accuracy.

## Implemented flow

1. Validate a separate runtime and pinned price-only champion. The runtime must
   not overlap the collector directory or serving model home. Use an injected
   aware scheduling time, the Singapore calendar, seven validation days and one
   full maturity day by default.
2. Lock the runtime and claim one `price-only-YEAR-WEEK` attempt. Repeated calls
   in that Singapore ISO week skip without overwriting evidence or fitting again,
   including after failure. Acquire one fresh SQLite hot backup read-only,
   including WAL contents; retain original source/receipt timestamps and build
   the immutable dataset. This does not poll any new upstream/paid service.
3. Save RUNNING through the existing shared admin login/CSRF audit contract
   **before acquisition/training**. A missing acknowledgement aborts training.
   Fit one fixed-parameter clone, with a globally distinct weekly version, and
   compare it with current/B1/B2/B3 on identical eligible points. The protected
   July/August study and September28 demo interval remain excluded from fitting.
4. Persist SUCCESS, REJECTED or FAILED with range, dataset identity and scores.
   SUCCESS means the shadow job completed, not promotion or team acceptance.
   No common pairs or worsened candidate MAE gives REJECTED; neither outcome
   authorizes adoption. Price/spike/savings gates and adequate support remain
   distinct from this completion diagnostic.
5. On an audit outage preserve the exact terminal payload locally with its hash.
   Explicit `sync` resends it without refitting. Database terminal attempts are
   immutable/idempotent and use `promotion_state=TRAINING_ATTEMPT`, so they cannot
   become forecast models. After a hard kill, sync also recovers an unlocked
   RUNNING attempt older than an hour, even without a pending marker. A normal
   SIGTERM is handled; the outer 45-minute limit has a further30-second hard stop.

There is no retry loop, model search, new baseline ranking, trial reset or
post-test refit. A failed week stays failed; do not create another runtime to
circumvent the one-attempt/week limit. Pending audit sync is an explicit operator
step, not a claim that failed HTTP delivery reached the database.

## Private configuration and disabled scheduling

Copy the [JSON template](../../scripts/forecast-price-only-weekly.example.json)
and [environment template](../../scripts/forecast-price-only-weekly.env.example)
outside Git. Set real source/champion paths, verify the manifest pin, use separate
persistent output storage, and keep credentials owner-only mode600. The environment
file is trusted shell configuration; it must be owned by the worker user. The
source SQLite database must be retained and backed up according to its source policy.

Render staged units; this command **cannot write into user/system systemd unit
directories** and does not call enable/start. It creates the separate runtime
directory needed by its restricted service write permission.

```sh
PYTHONPATH=ml/src /path/to/python -m wattly_ml.weekly_runtime render \
  --config /private/weekly.json --environment /private/weekly.env \
  --staging /private/staged-units --python /path/to/python --repo /trusted/checkout
systemd-analyze --user verify /private/staged-units/wattly-price-only-weekly.service \
  /private/staged-units/wattly-price-only-weekly.timer
```

The rendered schedule is Sunday03:00 Asia/Singapore. The wrapper supplies its
wall-clock tick explicitly; worker tests inject that time. The service limits
CPU, memory and duration and makes the filesystem read-only except for its
separate runtime. Neither file is installed/activated by this preparation.
The dedicated Python dependencies/checkout must be installed and readable
before any separately reviewed activation; WSL/user services must be available.
The same-week claim prevents a persistent timer catch-up from refitting twice.

An authorized manual shadow run would use the private environment in a separate
process, with explicit time. Do not invoke it against the live audit API merely
to test authentication; the integration tests use disposable fixtures instead.

```sh
python -m wattly_ml.weekly_runtime run --config /private/weekly.json \
  --as-of 2026-11-08T03:00:00+08:00
python -m wattly_ml.weekly_runtime sync --config /private/weekly.json \
  --as-of 2026-11-08T04:15:00+08:00
```

`run`/`sync` use `PYTHONPATH` and the shared admin credentials supplied to that
process. The scheduled wrapper requires session authentication and never enables
the optional local Basic filter. The API retains the existing HTTPS/loopback,
cookie, CSRF rotation and no-redirect protections. No credentials are written to
the model manifest, scores or rendered units.

## Tested experimental adoption and rollback sandbox

The [sandbox tool](../../ml/src/wattly_ml/weekly_sandbox.py) only accepts a newly
initialized, marked directory outside source/serving storage. Initialize copies
of the pinned champion into immutable `models/VERSION` directories. A stage
operation copies an explicitly pinned candidate and atomically switches
`experimental-release.json` with a required expected-old-hash check. The outgoing
release remains the rollback target. Bad pins, corrupt models, unsafe paths,
redirected model directories, duplicate versions and stale release hashes fail
closed. No `current.json`, production promotion flag or database trial changes.

```sh
python -m wattly_ml.weekly_sandbox initialize --sandbox /private/new-sandbox \
  --model /private/champion --manifest-sha256 CHAMPION_MANIFEST_SHA256
python -m wattly_ml.weekly_sandbox stage --sandbox /private/new-sandbox \
  --model /private/weekly-candidate --manifest-sha256 CANDIDATE_MANIFEST_SHA256 \
  --expected-release-sha256 CURRENT_RELEASE_SHA256
python -m wattly_ml.weekly_sandbox serve --sandbox /private/new-sandbox --port 18991
# Stop/restart only that sandbox server after switching or rollback.
python -m wattly_ml.weekly_sandbox rollback --sandbox /private/new-sandbox \
  --expected-release-sha256 CANDIDATE_RELEASE_SHA256
```

`serve` is loopback-only and refuses the live app/inference/database/bridge ports.
It loads the release's experimental manifest pin at startup, so restart is part
of the handoff. Tests start real isolated servers, check old predictions, switch
to different candidate predictions, restart, rollback, restart and confirm the
original predictions exactly. Source files and older model artifacts stay intact.
This tests the release mechanism; it does not approve an arbitrary candidate.

## Validation and activation boundary

68 focused Python tests pass, covering the new runtime/sandbox paths plus prior
price-only training/serving and scheduler/HTTP regressions. The DB-backed Java
acceptance scenario was run against a disposable PostgreSQL database: the new
weekly shadow payload round-trips, idempotent terminal replay succeeds, mutation
is rejected, and SUCCESS remains TRAINING_ATTEMPT with zero serving forecast runs.
The database was removed afterward. No full-suite/new-head GitHub CI run is claimed.

Activation still requires populated private credentials, sufficient input coverage,
team review of the checked-out code/schedule and the intended operator/runtime.
Actual production adoption additionally needs agreed data/support and
price/spike/savings acceptance gates and a separately identified evaluation/release
policy; the current experimental bundle remains `productionEligible=false`.
Do not replace its model within the existing frozen trial. A weekly-changing
model process can be evaluated separately on the same issue/target/horizon/truth
pairs; it is not the same estimand as one fixed-model trial.

Task59 remains In Progress/prepared while activation is intentionally deferred.
The 8November one-time spike score, existing collector operation and frozen trial
metadata are unchanged. This branch is local and has not modified PR48's review head.

## Local WSL preparation/readiness record — 2 November 2026

Private configuration and owner-only environment files were prepared, with admin
credentials intentionally blank. Both staged systemd units passed verification;
neither was installed or enabled. A fresh read-only snapshot of the existing local
collector contained1,942 rows over1,878 distinct periods. Using the existing
recorded mapping evidence and protected interval, the 25October training cutoff
yielded1,042 complete training origins out of8,760 for a full183-day window
(11.9% coverage). No candidate was fitted and no live audit API was contacted.
This does not count retrospective final archives with unknown historical receipt
times as genuine operational vintages. Sparse coverage is an outstanding data
limitation, not proof of a retraining improvement or permission to weaken gates.
The raw snapshot/dataset and rendered operator files remain private outside Git.

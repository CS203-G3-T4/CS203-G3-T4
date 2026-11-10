# Weekly retraining: deployment handoff [CSDT4-59]

This is a deployment procedure for the person responsible for Wattly hosting.
It needs no access to Bryan's desktop or WSL once the release artifacts have
been transferred. It does not deploy anything by itself.

## What is being deployed

A separate, one-shot Python worker retrains one price-only candidate each
Singapore ISO week from receipt-stamped price history. It compares the candidate
with the pinned current model and B1/B2/B3 on identical eligible forecast pairs.
It records the training range, dataset identity, outcome and scores through
Wattly's authenticated API into `model_run`. A worse candidate is REJECTED;
the current serving model stays in place. SUCCESS means the shadow job completed,
not that its model was adopted.

This follows task59's weekly/latest-data/audit/retain-worse requirements. It is
price-only; demand and solar are not required for this worker. The separate
task35 demand-model study is not replaced by weekly price-only training.
Adoption is deliberately separate while the existing comparison trial is frozen.

## Before touching the deployment

1. Have the required review, passing CI and merge for PR48. Deploy the compatible
   Java application first: the worker needs `/api/v1/admin/forecast-training-attempts`
   and the price-revision schema. Preserve the existing migration order and trial.
   Record the exact source commit and eventual worker image digest.
2. Use the existing always-on deployment and PostgreSQL environment. At preparation
   on 3 November, GitHub links the app to Railway and `https://wattlyapp.me`.
   Verify the actual destination before configuring credentials or database access.
   The Railway account is separate from Wattly's resident/admin accounts.
3. Obtain the trusted price-only model directory separately from the source repo:
   `manifest.json` and `models.joblib`. Record and verify the manifest SHA256.
   Keep its version, dependencies and `productionEligible=false` unchanged.
   Never put models, price archives, passwords or populated environment files in Git.
4. Make a read-only PostgreSQL account with SELECT on `market_price_revision`.
   Supply its PostgreSQL connection URL, not Spring's `jdbc:` URL. The worker
   never needs household/account SELECT permission or database write permission.
5. Supply a private, working Wattly ADMIN login for the deployed app. The resident
   account cannot save audits. Public local demo passwords are unsuitable for
   public hosting. Enter credentials directly into the hosting provider's secret
   variables; do not put them in commands, screenshots or this document.

## Build, model and retained storage

Build the reviewed Linux/amd64 worker with:

```sh
docker build -f ml/Dockerfile.weekly -t wattly-weekly:reviewed ml
```

The source-only image intentionally excludes all models and datasets. Provision
the trusted model as a read-only mount at `/inputs/models/champion`, or create a
private release image containing those two model files at that location. A
minimal second build context contains only this Dockerfile and `champion/`:

```dockerfile
FROM wattly-weekly:reviewed
COPY --chown=10001:10001 champion/ /inputs/models/champion/
```

Keep that build context outside Git. Use a trusted registry/release channel and
record its immutable image digest; do not deploy an unreviewed mutable tag.
The model remains separate from writable training output.

Attach retained POSIX storage at `/var/lib/wattly-weekly`, owned by UID/GID10001.
Verify cross-process locks and persistence across replacement of the worker.
Keep one worker, one scheduler and the same volume across releases. The image is
non-root; a provider-created root-owned volume needs ownership provisioning
before the readiness check. Do not silently change the permanent worker to root
or erase the volume to retry a week. Back up the retained attempt journals.

Railway documents its [volume permissions and limitations](https://docs.railway.com/volumes/reference).
An operator can provision ownership with a temporary administrative setup step;
the routine worker must return to UID10001. This must be tested on the actual
volume, not assumed from the image's directory ownership.

## Service settings

Start from [the private variable template](../../deploy/weekly.portable.env.example).
Use secret variables or mounted `_FILE` secrets, never both for the same value.

| Setting | Value / action |
| --- | --- |
| Worker image | Reviewed image plus the trusted model, pinned by digest |
| Start command for first readiness deployment | `python -m wattly_ml.portable_weekly check-admin` |
| HTTP healthcheck / public domain | None; this is a one-shot job, not a web server |
| Restart policy | Never; no automatic same-week retry |
| Replicas / schedulers | Exactly one |
| `WATTLY_WEEKLY_HOME` | `/var/lib/wattly-weekly` on the retained volume |
| `WATTLY_WEEKLY_CHAMPION` | `/inputs/models/champion` |
| `WATTLY_WEEKLY_CHAMPION_SHA256` | Verified hash of the transferred manifest |
| `WATTLY_WEEKLY_SOURCE` | `postgres` |
| `WATTLY_TRAINING_DATABASE_URL` | Private SELECT-only PostgreSQL URL |
| `WATTLY_RUN_API_URL` | Verified app HTTPS origin |
| `WATTLY_RUN_AUTH_MODE` | `session` |
| `WATTLY_ADMIN_USER`, `WATTLY_ADMIN_PASSWORD` | Existing private deployed ADMIN credentials |
| `WATTLY_WEEKLY_STORAGE_READY` | `true` only after retention/permissions are verified |
| `WATTLY_WEEKLY_SINGLE_WORKER` | `true` only after checking there is one worker |
| `WATTLY_WEEKLY_ENABLED` | Keep `false` during setup |
| `WATTLY_WEEKLY_NOT_BEFORE` | `2026-11-08T20:00:00+08:00` for this release |

If building the source-only service directly from GitHub on Railway, root is
`/ml` and Dockerfile is `Dockerfile.weekly`. That path still requires separately
provisioning the trusted model. Shipping only the Java jar or this source-only
image does not provide the model. See the [portable deployment guide](weekly-portable-deployment.md)
for the equivalent VPS/Compose setup.

## Readiness without training

`check-admin` validates model/dependencies, writable/locking storage and read-only
data access, then uses the shared login and rotated CSRF token to GET one audit
record. It must return `ready=true`, `authenticatedAdminRead=true`, `trained=false`,
`auditWritten=false` and `automaticAdoption=false`. Login creates an ordinary
authenticated session, but no training audit is submitted. It neither reads
price outcomes nor starts a weekly attempt.

A 401/403, login redirect/error, missing endpoint, incorrect JSON contract,
unwritable volume, model mismatch or missing source table blocks setup. Fix the
deployment or credentials; do not bypass auth or start training to test login.
Readiness does not prove enough timestamped training history is available.

Redeploy the same readiness command using the same volume and confirm retained
configuration survives. Qualify duplicate ticks, rejection and interrupted-audit
recovery against an isolated database using the tests below. Do not manufacture
a live training attempt for a smoke test.

## Activate later

For this release, wait until the reserved 8 November 20:00 Singapore spike score
has actually completed. A timestamp passing alone is not evidence it completed.
Do not inspect its outcomes early. After the operator records that completion:

1. Change the start command to `python -m wattly_ml.portable_weekly run`.
2. Set `WATTLY_WEEKLY_ENABLED=true` and keep the not-before guard.
3. Configure one Sunday 03:00 Asia/Singapore schedule. Railway uses UTC, so its
   [cron expression](https://docs.railway.com/cron-jobs) is `0 19 * * 6`.
   The first ordinary slot after this release's gate is 15 November 03:00 SGT.
4. Inspect the provider's actual next run and any deploy-time/catch-up execution.
   Do not trigger an extra off-schedule run just to demonstrate the feature.
5. After the first actual scheduled attempt, inspect both its terminal database
   audit and retained files. Record source/image/model pins, training range,
   validation range, pair support, scores and whether the audit is pending.

The worker has a 45-minute hard bound with 30 seconds for termination cleanup.
Railway must let the one-shot process exit; do not wrap it in an endless loop.
Set the provider's termination grace to allow the cleanup interval.

## Outcomes and recovery

| Outcome | Operator action |
| --- | --- |
| SUCCESS | Candidate comparison completed. Keep the serving model frozen; arrange separate review before adoption. |
| REJECTED | Retain current model. Inspect scores/support; do not refit repeatedly. |
| FAILED | Inspect the existing attempt. Preserve its evidence; do not bypass the weekly claim. |
| `auditPending=true` / interrupted delivery | Run `python -m wattly_ml.portable_weekly sync` with the same volume/config/secrets. It resends retained audits without fitting. |
| `SKIPPED_ALREADY_ATTEMPTED` | Expected for a duplicate same-week tick. Do not erase state to try again. |
| Disabled / not-before skip | No candidate is fitted. Check settings and the reserved-study gate. |

To stop future work, remove/disable the schedule and set ENABLED=false; terminate
any current worker separately if needed, then reconcile its journal. Keep the
volume and database audits. This worker never changes a serving pointer, so
stopping it requires no live-model rollback or trial/database reset.

## Checks and completion evidence

In a disposable test environment, set `WATTLY_WEEKLY_TEST_DATABASE_URL`, then run:

```sh
python -m pytest -q ml/tests/test_price_only_weekly.py ml/tests/test_weekly_runtime.py \
  ml/tests/test_portable_weekly.py ml/tests/test_weekly_postgres.py \
  ml/tests/test_weekly_sandbox.py ml/tests/test_operations_http.py
```

PostgreSQL tests must run, not skip. CI additionally builds/runs the non-root
container. Synthetic fits and sandbox adoption/rollback test the mechanism;
they do not establish real model improvement or permission to swap the live model.

Deployment is not complete until the actual host's readiness, retained storage,
schedule and first bounded real attempt/audit have been verified. This handoff
does not claim activation, sufficient data, improved accuracy or Jira closure.

Local verification on 3 November: all 90 checks in the six Python modules above
passed, including the PostgreSQL cases with zero skips. The existing Java
`ForecastAcceptancePersistenceTest` also passed against the same disposable
PostgreSQL instance; its scenario verifies audit storage, idempotency, immutable
terminal records and keeping shadow candidates out of serving. The database
was stopped afterward. New readiness checks cover successful shared login,
bad credentials, role denial, incompatible responses and zero training/audit
writes. These results are local verification, not a Railway deployment result
or a claim that new-head CI has already passed.

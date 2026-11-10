# Portable weekly training [CSDT4-59]

The [deployer handoff](weekly-deployer-handoff.md) gives the complete setup,
readiness, delayed activation and recovery procedure for the hosting owner.

The same one-shot worker runs on a Linux VPS, a Linux container on a Docker host,
or a managed platform with scheduled container jobs. It does not need WSL,
systemd, a logged-in desktop, a GPU, a browser collector or the developer's laptop.
It does need Python3.12 and the model's exact dependency contract (provided by
the image), enough CPU/memory/time, retained POSIX storage with working process
locks, the trusted model bundle, current timestamped prices and the admin audit
API. An ephemeral-only/serverless host without those facilities is unsupported;
there is no promise to run on every arbitrary environment.

The worker **trains/evaluates candidates only**. It never changes a serving
pointer, frozen trial, policy or production eligibility. The separate experimental
release sandbox remains a rehearsal. This portability change is not production
promotion integration, a new data license or evidence of better accuracy.

## Runtime contract

Use [the private environment template](../../deploy/weekly.portable.env.example).
Variables or mounted `_FILE` secrets supply the existing shared ADMIN account
and read-only PostgreSQL connection. Do not set both forms of one secret. Only
HTTPS or loopback HTTP is allowed for the audit API; private HTTP across separate
containers is not silently exempted. Use the deployment's protected HTTPS URL.
Real public deployments need private credentials, not the repository's demo
passwords. No value is embedded in the image, effective config, model or log.

Provision a trusted price-only champion and pin `manifest.json` with SHA256.
The image pins Python3.12.3 and library versions to the existing bundle contract.
Do not disable version checks to load a model built with another runtime. Build
an appropriately pinned image and qualify it with the tests instead. The model
is a trusted operator-provisioned joblib, never an end-user upload.

`WATTLY_WEEKLY_HOME` is a separate persistent output directory. Mount it on
retained storage, keep its content across redeploys, and back it up. Do not put it
inside the source/model directory, app database directory or `WATTLY_ML_HOME`.
Confirm `WATTLY_WEEKLY_STORAGE_READY=true` only after configuring the actual
volume. The check verifies write/fsync and cross-process lock exclusion, but
cannot prove a provider will retain a directory after container destruction.

Use exactly one worker/scheduler per app and one shared durable runtime, then
confirm `WATTLY_WEEKLY_SINGLE_WORKER=true`. Local flock plus retained week claims
prevent concurrent/repeated fitting on that runtime. **These are not distributed
leases across unrelated disks**. Do not scale separate copies with different
volumes or erase the runtime to retry a failed week. A migration must transfer
the retained attempts/configuration along with the outputs.

## Data adapters

Default `postgres` reads the deployed app's `market_price_revision` using a
repeatable-read, read-only transaction, bounded query timeout and250,000-row cap.
Use a dedicated account granted SELECT on that table. It exports NEMS_SN_SG
provisional revisions only, keeps the original publication and availability times,
filters to the explicit acquisition time and retains revisions rather than
collapsing each period to its final value. It reads no households or accounts.
No receipt times are invented and no upstream market site is polled by training.
For an unavailable source or insufficient eligible history, retain the current
model and record failure/rejection. A new server with no archive must accumulate
or import genuinely timestamped history; it cannot borrow imaginary past receipts.

`sqlite` uses the existing read-only SQLite hot-backup path, including WAL.
Mount the collector directory read-only, including its WAL/SHM, then set
`WATTLY_WEEKLY_COLLECTOR`. PostgreSQL needs no collector file on the laptop.
The v1 WSL/systemd configuration still uses its original mandatory mount guard.
The portable adapter uses read-only DB connections plus pinned model/source
integrity. It does not falsely claim that every managed platform provides the
systemd read-only mount namespace. Prefer read-only model/source mounts and a
read-only container root where supported, with only output and temporary storage
writable. These deployment mechanisms differ; data/model checks are retained.

The existing seven-day paired validation, one maturity day,183-day training
window, protected study dates and fixed-parameter clone are preserved. SUCCESS
means a completed shadow job, never an accepted replacement. MAE worsening or
zero common pairs is REJECTED. The sole earlier real-data candidate was rejected;
portability is not an accuracy improvement or a reason to retry that experiment.

## Commands and scheduling

With the private environment injected, use:

```sh
python -m wattly_ml.portable_weekly check
python -m wattly_ml.portable_weekly check-admin
python -m wattly_ml.portable_weekly run
python -m wattly_ml.portable_weekly sync
```

`check` loads the trusted pinned model, verifies dependencies, storage/process
locks and read-only source access. It writes only local configuration/preflight
files, makes no training-audit POST and performs no fit or model adoption.
`check-admin` also authenticates with shared login/rotated CSRF and reads the
training-audit endpoint. It rejects missing/incompatible endpoints or a denied
account without submitting an audit or claiming a weekly attempt.
Normal `run` stays disabled unless `WATTLY_WEEKLY_ENABLED=true`. It also skips
before `WATTLY_WEEKLY_NOT_BEFORE`, without claiming a weekly attempt. Its wrapper
supplies the real scheduling time to the core; tests can inject `--as-of`.

The worker exits when finished. The caller enforces45minutes, sends SIGTERM and
allows30seconds for the existing audit/spool handler before killing it. A hard
kill is recovered through explicit `sync`, without another fit. Nonzero exit
means failure/pending audit. REJECTED is a completed run and exits zero. Do not
configure automatic same-week retries. Read database audit outcomes, not just
exit0, when deciding whether a candidate improved. Schedule explicit sync and
investigate failed/pending records as an operator action.

On a VPS, invoke that command from an OS scheduler or use the container below.
On Railway, configure a separate scheduled service with root directory `/ml`,
`RAILWAY_DOCKERFILE_PATH=Dockerfile.weekly`, and the full start command
`python -m wattly_ml.portable_weekly run`, retained output storage and the same
secrets/model pin. A custom Railway start command replaces the image entrypoint;
`run` alone is not an executable there. A model directory must also be provisioned
there as part of release setup; shipping the Java jar alone does not include it.
Separate services cannot assume shared local disks: provide each with its needed
artifact/data or use a reviewed artifact-distribution mechanism.

Example target schedule: Sunday03:00 Asia/Singapore. Railway cron is UTC:
`0 19 * * 6` is Saturday19:00 UTC / Sunday03:00 Singapore. Configure only one
scheduler; the image itself contains no periodic loop. Keep this release off
until the reserved8November20:00 Singapore score completes; the first ordinary
training slot afterward is15November03:00. Check persistent catch-up state.

Railway's [scheduled jobs](https://docs.railway.com/cron-jobs) and
[persistent volumes](https://docs.railway.com/volumes/reference) document the
provider facilities; their existence does not mean this project is deployed there.

## Container / VPS example

Build from a reviewed source commit:

```sh
docker build -f ml/Dockerfile.weekly -t wattly-weekly:reviewed ml
```

The default is a non-root one-shot process. The build context whitelists code and
dependency metadata; it excludes models, datasets and private environments.
Use [the Compose worker](../../deploy/weekly.compose.yml) with a private
`WATTLY_WEEKLY_ENV_FILE` and `WATTLY_CHAMPION_HOST_PATH`. Its read-only root/model
mount, retained output volume, temporary tmpfs, CPU1/memory2GiB caps and disabled
restart prevent it from taking over serving or repeatedly searching candidates.
Provision input/output permissions for container UID10001 before running.

```sh
docker compose -f deploy/weekly.compose.yml run --rm weekly check
# After reviewed activation, a server scheduler can invoke this once per week:
docker compose -f deploy/weekly.compose.yml run --rm weekly run
```

No ports are exposed by the training container. Java and inference continue
serving the existing model. Current experimental Java→Python inference is
loopback-only: a separately networked inference service needs a reviewed
deployment/security change, not an automatic relaxation by this worker.

## Deployment acceptance

The image/adapter tests run on Linuxamd64 with synthetic trusted models, a local
HTTP audit fixture and disposable PostgreSQL. Other CPU architectures and actual
managed-provider accounts must pass the same checks before claiming support.
Native Windows Python is not supported by POSIX flock; Windows/macOS can use a
qualified Linux-container runtime. Nothing can retrain on a powered-off host.

Before calling a deployment complete, verify a retained runtime survives a
restart, a real server-source snapshot preserves timestamps, a synthetic test
account reaches an isolated audit DB, duplicate ticks do not fit twice, a bad
candidate retains serving, and timeout/audit recovery is exercised. Then verify
the separately authorized first real server attempt and its audit. Keep required
PR review, actual server activation and live-model adoption distinct. No server
account, external service, trial or timer is changed by these source/templates.

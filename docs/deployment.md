# Deploying to Railway

The service runs on [Railway](https://railway.com) as one always-on web service plus
a Railway-managed PostgreSQL database. It must stay running: NEMS is polled every
five minutes and the weather and recommendation jobs are `@Scheduled`, so a host
that sleeps idle apps leaves gaps in `price_interval`.

## Production profile

`src/main/resources/application-prod.yml` (CSDT4-69) is activated with
`SPRING_PROFILES_ACTIVE=prod` and overrides `application.yml`:

| Setting | Production behaviour |
| --- | --- |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | Required. No defaults, so startup fails instead of falling back to the local database. |
| `DB_POOL_SIZE` | Optional, default `5`. |
| `PORT` | Set by Railway; the server listens on it (default `8080`). |
| Proxy headers | `X-Forwarded-*` from Railway's proxy are trusted, so links use `https`. |
| Errors | Messages and stack traces are never returned to clients. |
| `/actuator/health` | Returns only `UP`/`DOWN`, no component details. |

Every other variable in `application.yml` (`USEP_FEED_URL`, `FORECAST_PYTHON_URL`,
`RECOMMENDATION_FORECAST_SOURCE`, …) keeps its default and can be overridden the
same way. Without the Python forecast service, F3 falls back to the Java baseline.

Secrets live only in Railway's variables. Never commit them or put them in this file.

## One-time setup

1. Sign in to Railway with GitHub and create a project with **Deploy from GitHub
   repo**, choosing `CS203-G3-T4/CS203-G3-T4` and the `main` branch.
2. In the project, add **Database → PostgreSQL**. Keep the service name `Postgres`
   (the variable references below use it).
3. Open the app service's **Variables** tab and add:

   ```text
   SPRING_PROFILES_ACTIVE=prod
   DB_URL=jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}
   DB_USER=${{Postgres.PGUSER}}
   DB_PASSWORD=${{Postgres.PGPASSWORD}}
   RAILPACK_JDK_VERSION=21
   ```

   The `${{...}}` references are resolved by Railway over its private network, so
   the database password is never copied by hand. Spring Boot needs a `jdbc:` URL,
   which is why `DB_URL` is built from parts instead of using `DATABASE_URL`.
   To be able to sign in, also set the login variables in [Logins](#logins)
   (this changes `SPRING_PROFILES_ACTIVE` to `prod,demo`) before the first deploy.
4. In the app service's **Settings**:
   - **Networking → Generate Domain** to get a public `*.up.railway.app` URL.
   - **Deploy → Healthcheck Path**: `/actuator/health`.
   - **Deploy → Serverless / App Sleeping**: off.
   - **Replicas**: 1. More replicas would each run the scheduled polls.
5. Deploy. Railway builds with the Maven wrapper and runs the jar from `target/`.
   Flyway applies all migrations on first start.

## Logins

Accounts are created only when the `demo` profile is active: `DemoAccountSeeder`
creates `resident` (linked to the Home household) and `admin`. Without `demo`,
nobody can sign in. The admin role can use `/api/v1/admin/**` and create or delete
households.

The default passwords (`resident-demo`, `admin-demo`) are in `application-demo.yml`
and the README, and this repository is public. Use them for local development
only. On Railway, activate `demo` and replace both passwords:

```text
SPRING_PROFILES_ACTIVE=prod,demo
WATTLY_DEMO_ACCOUNTS_ADMIN_PASSWORD=<long random value>
WATTLY_DEMO_ACCOUNTS_RESIDENT_PASSWORD=<long random value>
```

- Set these **before the first start**. The seeder only inserts accounts that are
  missing (`ON CONFLICT DO NOTHING`), so changing a variable later does not change
  an account that already exists. To rotate a password, run
  `DELETE FROM user_account WHERE username IN ('admin', 'resident');` and redeploy.
- The usernames stay `admin` and `resident`. Share the passwords with the team
  privately, never in Git, chat logs, or screenshots.
- Startup fails if the Home household is missing, so run it on a database that has
  applied the seed migrations (the first Flyway run does).
- Checked locally on 19 October 2026 with a temporary PostgreSQL database: with
  `WATTLY_DEMO_ACCOUNTS_ADMIN_PASSWORD` set, `admin-demo` was rejected and the new
  password signed in. An account whose variable is not set keeps its public default.
  This was not run on Railway.

## Check a deployment

- `https://<domain>/actuator/health` returns `{"status":"UP"}`.
- The deploy logs show Flyway migrating and the first NEMS poll.
- `https://<domain>/dashboard.html` shows a current price.

CSDT4-70 then covers the 24-hour check for missing half-hours.

## Recovery

- **App fails to start with `'url' must start with "jdbc"`:** `DB_URL` is missing
  or wrong. Check the variables from step 3 and that the database service is
  named `Postgres`.
- **Bad release:** in the app service's **Deployments** list, redeploy the last
  good deployment. Flyway migrations are not rolled back; a migration that needs
  reversing must be fixed by a new migration.
- **Data:** Railway's PostgreSQL volume persists across app redeploys. Take a
  `pg_dump` (Postgres service → **Connect** gives the public URL) before risky
  migrations.

## AI forecast container

By default Railway builds the jar alone and F3 serves the Java baselines. The
experimental AI model only answers over loopback (`PythonForecastClient` refuses
any other host), so a separate Railway service cannot serve it.
[`deploy/app-with-forecast.Dockerfile`](../deploy/app-with-forecast.Dockerfile)
runs Spring and the Python forecast service in the same container instead.
Python listens on `127.0.0.1:8001` only and is never published. It is opt-in, and
enabling experimental AI on the public site is a team decision.

0. **Try it outside the presentation domain first.** The amd64 build on Railway and
   Railway's use of `app-with-forecast.Dockerfile.dockerignore` have not been
   tested yet (the image was only built and run locally on arm64). Create a
   separate Railway environment (for example `staging`) with its own PostgreSQL,
   follow the steps below there, and only then repeat them on wattlyapp.me.
1. **Volume:** on the app service, add a volume mounted at `/data`.
2. **Model:** the bundle (`models/<version>/manifest.json` and `models.joblib`)
   never goes into Git or the image. Put the zip at a private HTTPS URL, for
   example a presigned link from a Railway bucket. The container downloads it to
   the volume once, and only after the manifest matches the pinned SHA-256 and the
   model matches its manifest.
3. **Variables** on the app service (keep the existing ones):

   ```text
   RAILWAY_DOCKERFILE_PATH=deploy/app-with-forecast.Dockerfile
   WATTLY_ML_HOME=/data/wattly-ml
   WATTLY_MODEL_ZIP_URL=<private link to the bundle zip>
   WATTLY_ALLOW_EXPERIMENTAL_AI=true
   WATTLY_EXPERIMENTAL_MODEL_VERSION=<bundle version>
   WATTLY_EXPERIMENTAL_MANIFEST_SHA256=<SHA-256 of its manifest.json>
   FORECAST_PERIOD_MAPPING_EVIDENCE=EMC_RT_20260929_24_PERIODS
   JAVA_OPTS=-XX:MaxRAMPercentage=50
   ```

   Once the deploy logs show `Forecast model <version> installed`, you can remove
   `WATTLY_MODEL_ZIP_URL`; later starts log `already present`.

   `FORECAST_PERIOD_MAPPING_EVIDENCE` is not only for the AI model.
   `ForecastService` uses it to clear the `PERIOD_MAPPING_UNVERIFIED` flag in
   `ForecastJob`, and it also gates the F2 assessment (`CurrentAssessmentService`)
   and F4 (`LiveComparisonService`). Setting it makes the Java baselines
   actionable too, so it is part of the same team decision.

   **Memory:** the JVM and Python now share the service's memory limit. Without
   `JAVA_OPTS` the JVM sizes its heap from the whole container limit, so cap it at
   half. In a local run (10 November 2026), the Python service with this model
   loaded used about 145 MB RSS. Check the app service's memory limit in Railway
   before enabling it; the JVM figure on Railway has not been measured.
4. **Check:** `https://<domain>/api/v1/forecast/latest` shows
   `"modelType":"AI"` after the next half-hour price. `PYTHON_UNAVAILABLE_OR_INVALID`
   means Python is not ready: look for `Model not ready` in the deploy logs.

If the download or model check fails, Spring still starts and falls back to the
baselines; the logs give only the error type, never the URL. If the Python
process exits, it is restarted after 10 seconds (`Forecast service exited;
restarting in 10s`). Without `WATTLY_ML_HOME` the image behaves like the default
build.

To switch back to today's state, delete `RAILWAY_DOCKERFILE_PATH`,
`WATTLY_ALLOW_EXPERIMENTAL_AI` **and** `FORECAST_PERIOD_MAPPING_EVIDENCE`, then
redeploy; the volume can stay. Leaving `FORECAST_PERIOD_MAPPING_EVIDENCE` set
keeps the B1 baseline and F2/F4 actionable on the live site.

## Weekly model worker

Follow the [weekly deployer handoff](forecasting/weekly-deployer-handoff.md) for
the complete later deployment, including private model provisioning, volume
permissions, non-training authenticated readiness and the first-run evidence.

Deploying the Java service does not install the Python model or weekly trainer.
The [portable weekly worker](forecasting/weekly-portable-deployment.md) has a
separate container/one-shot command, PostgreSQL or SQLite read-only acquisition,
retained output storage, private secrets and deployment checks. It can run on
the server independently of a developer laptop. Configure one external schedule
and one durable worker runtime; it is disabled until explicitly enabled.
The worker only evaluates candidates and does not replace the frozen live model.

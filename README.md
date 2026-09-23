# CS203 Household Energy Market Service

This Spring Boot service stores half-hourly Uniform Singapore Energy Price (USEP) observations in PostgreSQL. It polls the [NEMS API](https://nems.sn.sg/) when the application starts and at 02 and 32 minutes past each hour in Singapore time. The feed is an unofficial public service; the database retains the last known value when it fails.

USEP is expressed in **SGD/MWh**. It is a wholesale market signal, not a complete household electricity tariff or bill rate.

## Run locally

Requirements: Java 21 or newer, and Docker with Compose (or a PostgreSQL server). The Maven wrapper downloads Maven automatically.

```sh
docker compose up -d postgres
./mvnw spring-boot:run
```

The default PostgreSQL connection is `jdbc:postgresql://localhost:55432/energy_market` with local development credentials `energy_market` / `energy_market`. Override it with `DB_URL`, `DB_USER`, and `DB_PASSWORD` for another database. The container port can be changed with `POSTGRES_PORT`.

The application applies the Flyway migration automatically. The source URL can be overridden with `USEP_FEED_URL`. Set `USEP_POLLING_ENABLED=false` to disable the startup and scheduled polls, such as when running against a prepared test database.

## API

- `GET /api/v1/market-prices/latest` returns the latest stored price and `LIVE` or `STALE` freshness.
- `GET /api/v1/market-prices?from=2026-09-23T00:00:00Z&to=2026-09-24T00:00:00Z` returns historical intervals. The range is at most 31 days.
- `GET /api/v1/market-prices/feed-status` reports the most recent poll result (`SUCCESS`, `STALE_SOURCE`, `FAILURE`, or `NEVER_POLLED`).
- `GET /actuator/health` reports application and database health.
- `/swagger-ui.html` displays the API documentation; `/v3/api-docs` returns the OpenAPI document.

`/latest` returns HTTP 503 with `freshness: UNAVAILABLE` if no price has ever been stored. Once a price has been stored, a feed outage does not erase it; the endpoint returns that value with `freshness: STALE`. A successful poll can still be classified as `STALE_SOURCE` if the upstream API responds with an old timestamp. The freshness limit defaults to 40 minutes.

## Tests

```sh
./mvnw test
```

The service targets Java 21. Tests currently cover timestamp normalization and the live, stale, and unavailable decisions. The database upsert uses PostgreSQL `ON CONFLICT` so repeated polls of an interval update one row rather than creating duplicates.

# CS203 Household Energy Market Service

This Spring Boot service stores half-hourly Uniform Singapore Energy Price (USEP)
observations and Singapore two-hour weather forecasts in PostgreSQL. It polls the
[NEMS API](https://nems.sn.sg/) and NEA when the application starts and at 02 and 32
minutes past each hour in Singapore time. NEMS is an unofficial public service.
Each feed retains its saved history when an upstream fails. No CSV files are written.

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

## Combined price and weather history

`V2__combined_observations.sql` adds `market_observation` (one distinct NEMS
payload, with first collection and last seen timestamps) and `weather_observation`
(one NEA issue/update pair, with its full forecast item). Existing `market_price`,
API endpoints, and NEMS ingestion status continue to operate.

The existing scheduler calls both feeds at startup and at :02/:32 Singapore time.
`USEP_POLL_CRON` overrides the shared schedule; `USEP_POLLING_ENABLED=false`
disables both feeds. `NEA_FEED_URL` overrides the default official endpoint:
https://api-open.data.gov.sg/v2/real-time/api/two-hr-forecast
Both clients use the existing bounded connection/read timeouts. Each feed is saved
independently. Upstream failures never delete prior rows; NEA failures are logged.
Each NEA response is saved transactionally, so invalid items roll back that batch.

Query `SELECT * FROM combined_observations ORDER BY source_updated_unix` for the
same 66-column layout and column order as `combined_2026-09-23 (1).csv`:

- `record_id`: SHA-256 of PostgreSQL's canonical price JSON; stable within this
  application, not guaranteed to reproduce the sample generator's hash.
- `source_updated_sgt`, `source_updated_unix`, `first_collected_sgt`,
  `last_seen_sgt`, `source_age_seconds_at_collection`, `feed_stale_at_collection`:
  source time, collection history and freshness at first collection (40-minute
  threshold by default). Duplicate prices only advance last seen.
- `usep_sgd_per_mwh`, `demand_forecast_mw`, `vcp_sgd_per_mwh`: NEMS `usep`,
  `demand`, `vcp`; missing VCP remains SQL NULL.
- `weather_match_status`, `weather_issued_sgt`, `weather_updated_sgt`,
  `weather_valid_start_sgt`, `weather_valid_end_sgt`: latest stored forecast issued
  AND updated no later than the price source time, with start <= source < end.
  Status is `matched_as_of_source_update` or `no_matching_weather`. Unmatched
  weather columns remain NULL. The view can fill a match when weather arrives later.
- `price_response_id`, `weather_response_id`: local observation IDs, not upstream
  IDs or the sample's capture-log IDs; repeated observations retain their IDs.
- 47 `weather_<Area_Name>` columns, in the sample's order (Ang Mo Kio through
  Yishun), holding each area's forecast. Additional future areas remain in JSON.
- `price_payload_json`, `weather_item_json`: the four mapped NEMS fields and the
  complete NEA item respectively, stored as JSONB.

Storage uses timezone-aware timestamps; the view formats `_sgt` columns as ISO
8601 strings with `+08:00`. This preserves a compatible shape for future CSV demo
import/export. No demo importer or automatic loading of the supplied CSV is enabled.
Existing historical market rows are not backfilled: their original first-collection
metadata is unavailable. Combined history starts with the next successful poll.

`src/test/resources/combined_observations_test.sql` validates the view on an isolated
PostgreSQL database after applying migrations, using `psql -v ON_ERROR_STOP=1 -f`.

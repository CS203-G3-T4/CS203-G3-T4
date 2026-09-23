# Jira alignment — 23 September 2026

This maps the backend to the descriptions and acceptance criteria read from the
CSDT4 backlog. It is an implementation audit, not a change to Jira status or an
agreement on behalf of other feature leads. Keep partial stories open.

| Jira item | Sprint | Delivered in this repository | Remaining acceptance work |
| --- | --- | --- | --- |
| [CSDT4-2 — Live Market Engine and Resilience](https://csd-t4.atlassian.net/browse/CSDT4-2) | Not shown on the issue | Spring Boot/PostgreSQL, 30-minute NEMS ingestion, idempotent intervals, latest/history API, source timestamps, ingestion outcomes, stale fallback and empty-store 503 | Period attribution remains subject to CSDT4-11 verification |
| [CSDT4-9 — Agree and document team conventions and hand-off interfaces](https://csd-t4.atlassian.net/browse/CSDT4-9) | 1 | Backend endpoint contracts and units documented below; shared clock and market/common packages | Team meeting/approval and interfaces for HouseholdDirectory, ForecastService, PriceHistory and demo ownership; no signatures invented for absent features |
| [CSDT4-10 — Publish API docs with Swagger UI (springdoc)](https://csd-t4.atlassian.net/browse/CSDT4-10) | 1 | Swagger UI, summaries on every application endpoint, feature tags for prices and weather | Keep documentation current as other features arrive |
| [CSDT4-11 — Spike: confirm NEMS feed timing and which half-hour each price belongs to](https://csd-t4.atlassian.net/browse/CSDT4-11) | 1 | Existing interval rule and its uncertainty documented below | Repeated observations across a boundary and comparison against EMC's published table; not completed by these commits |
| [CSDT4-13 — Show today's live price on the dashboard with a stale banner](https://csd-t4.atlassian.net/browse/CSDT4-13) | 1 | `/api/v1/prices/current`, one-decimal cents/kWh, source update time, stale flag and unavailable 503 | Frontend card, stale banner, one-minute refresh, empty-state dash and removal of hard-coded values |
| [CSDT4-21 — Adopt the shared Clock rule across the codebase](https://csd-t4.atlassian.net/browse/CSDT4-21) | 1 | `common/TimeConfig`, Asia/Singapore Clock, business-time injection, fixed-clock tests, PR checklist | Each feature lead must confirm the rule; DemoClock itself belongs to CSDT4-43 |
| [CSDT4-25 — Feed health status for the admin view](https://csd-t4.atlassian.net/browse/CSDT4-25) | 2 | Existing last-poll status is a prerequisite only | FeedHealth, consecutive failures, feed_incident lifecycle, monthly uptime/fallback metrics and `/api/v1/admin/feed-status` are not implemented |
| [CSDT4-26 — Outage switch for demos and tests](https://csd-t4.atlassian.net/browse/CSDT4-26) | 2 | Configurable `USEP_FEED_URL` and outage tests are prerequisites only | Named `wattly.nems.base-url` contract, authorized runtime toggle, recommendation guard and recovery without restart |
| [CSDT4-28 — Weather hint on the dashboard](https://csd-t4.atlassian.net/browse/CSDT4-28) | 2 | Separate NEA 24-hour client, stored daily high/low and text, 3-hour refresh, `/api/v1/weather/today`, configurable heat threshold, resilient fallback | Dashboard hint and team agreement on threshold (33°C is a configurable default, not an agreed decision) |

## Existing user-requested collection

The two-hour NEA area forecasts, 30-minute collection cadence, and 66-column
`combined_observations` view implement the user's CSV/demo data requirement.
They are retained alongside the daily weather feature. CSDT4-28 explicitly asks
for the **24-hour** API: the two-hour collector alone does not satisfy it.
No dedicated PBI for the combined CSV-shaped dataset was identified in the
reviewed backlog. Its commits use descriptive subjects instead of claiming
CSDT4-28 completion. Demo replay/import remains future work.

## Current endpoint contracts (F1)

All timestamps in JSON are ISO-8601 instants. Convert them to Asia/Singapore for
display. Use the injected Clock for current time and for choosing today's date.
Persist USEP as SGD/MWh, demand as MW, money as NUMERIC/BigDecimal. Display cents
per kWh = SGD/MWh / 10; cost in SGD = kWh × SGD/MWh / 1000. Appliance power is kW
and energy is kWh in future household interfaces.

`GET /api/v1/prices/current` (CSDT4-13 backend):

```json
{
  "marketPrice": {
    "price": 280.03,
    "unit": "SGD_PER_MWH",
    "forecastDemandMw": 7560,
    "intervalStart": "2026-09-23T06:00:00Z",
    "sourceUpdatedAt": "2026-09-23T06:01:00Z",
    "fetchedAt": "2026-09-23T06:02:00Z",
    "source": "NEMS_SN_SG",
    "freshness": "LIVE"
  },
  "centsPerKwh": 28.0,
  "stale": false
}
```

When there is no stored price, HTTP 503 has `freshness: "UNAVAILABLE"` in its
problem detail. The dashboard should render a dash. A feed failure returns the
last saved price with `stale: true` and `marketPrice.freshness: "STALE"`.

Existing contracts stay available:

- `GET /api/v1/market-prices/latest`: the `marketPrice` object above.
- `GET /api/v1/market-prices?from=<instant>&to=<instant>`: an array of MarketPrice
  records: source, intervalStart, sourceUpdatedAt, fetchedAt, usepSgdPerMwh,
  forecastDemandMw, vcpSgdPerMwh. Start inclusive, end exclusive, maximum 31 days.
- `GET /api/v1/market-prices/feed-status`: last poll's status, startedAt,
  finishedAt, sourceUpdatedAt, errorMessage. This is not the CSDT4-25 admin API.
- `GET /api/v1/weather/today`: `available`, `forecast`, `stale`, `heatHint`,
  `heatThresholdC`. The forecast contains date, issuedAt, updatedAt, validStart,
  validEnd, temperatureHighC, temperatureLowC, forecast (text), fetchedAt.
  No record for today's Singapore date returns HTTP 200 with `available: false`,
  `forecast: null`, `stale: true`, `heatHint: false`. An upstream outage retains
  today's saved forecast and marks it stale; stale forecasts never trigger a
  heat hint. The last-poll failure flag is process-local; timestamps still detect
  expired/old data after restart. A hint is a temperature signal, not a price prediction.

## Feed timing — open CSDT4-11 question

Current rule: `period_start = floor(updated_unix / 1800) * 1800`. The code's
property is named `intervalStart`; it represents the bucket containing the API's
source update time, not a separately confirmed EMC settlement period.

The sample CSV has updates at 15:01, 15:31, 16:01 and 16:31 SGT on 23 Sep 2026.
That is consistent with timestamps one minute after boundaries, but collection
was not continuous and does not establish publication latency or whether a price
belongs to the starting versus preceding half-hour. Do not shift the stored
interval by 30 minutes without the EMC comparison required by the spike.

## Daily weather source and configuration

Verified against the official live response on 23 September 2026:
https://api-open.data.gov.sg/v2/real-time/api/twenty-four-hr-forecast

The daily response uses `data.records`, `date`, `timestamp`, `updatedTimestamp`,
`general.validPeriod`, `general.temperature.high/low`, and `general.forecast.text`.
The two-hour response instead uses `data.items` and snake_case timestamps.
`V3__daily_weather.sql` stores the latest source revision per forecast date; older
revisions cannot replace newer ones. Batch writes are transactional.

- `NEA_DAILY_FEED_URL`: daily upstream URL.
- `NEA_DAILY_POLLING_ENABLED`: defaults to `USEP_POLLING_ENABLED`.
- `NEA_DAILY_POLL_CRON`: default `0 5 */3 * * *` (every three hours at :05 SGT), plus startup.
- `WEATHER_HEAT_THRESHOLD_C`: default 33; threshold comparison is inclusive.
- `weather.daily.stale-after`: default 6h since collection; validity expiry or a
  failed poll also makes a saved forecast stale.

The daily weather API does not replace or change the two-hour CSV-shaped view.

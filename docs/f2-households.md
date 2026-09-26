# F2 — Household Profiling & Simulated Loads

Jira epic: [CSDT4-4](https://csd-t4.atlassian.net/browse/CSDT4-4).
Code: `src/main/java/sg/edu/smu/cs203/household` (households), `.../household/appliance`
(appliances), `.../household/load` (archetypes and modelled load).

## What this delivers

| Jira | Delivered |
| --- | --- |
| [CSDT4-14](https://csd-t4.atlassian.net/browse/CSDT4-14) Store households with their electricity plan | `household` table (V4), `GET/PUT /api/v1/households/{id}`, `POST /api/v1/households`, `GET /api/v1/households`; fixed-rate households are flagged `exposedToWholesalePrice: false` ("Not exposed"); unknown id → 404 |
| [CSDT4-15](https://csd-t4.atlassian.net/browse/CSDT4-15) Appliance CRUD API | `appliance` table (V4); `GET/POST /api/v1/households/{id}/appliances`, `GET/PUT/DELETE /api/v1/households/{id}/appliances/{applianceId}`; 400 with a message per field; windows may cross midnight |
| [CSDT4-16](https://csd-t4.atlassian.net/browse/CSDT4-16) Appliances page | `static/appliances.html`: list, add, edit, delete, on/off switch, "Power (kW)" and "Run time" labels, API messages shown next to fields |
| [CSDT4-29](https://csd-t4.atlassian.net/browse/CSDT4-29) Archetypes calibrated to EMA | `load_archetype` table (V6) with EMA 2024 averages; `LoadProfileCalculator` (method below); unit tests prove the month adds up to EMA's figure |
| [CSDT4-30](https://csd-t4.atlassian.net/browse/CSDT4-30) Load profile endpoint | `GET /api/v1/households/{id}/load-profile?date=YYYY-MM-DD` (48 half-hour kWh values; date defaults to today in Singapore); `HouseholdDirectory` interface for F4 |
| [CSDT4-31](https://csd-t4.atlassian.net/browse/CSDT4-31) Demo households | The Tans (V5) plus Lim, Ong, Nair and the fixed-rate Rahman Residence (V7) |
| [CSDT4-32](https://csd-t4.atlassian.net/browse/CSDT4-32) Settings page | `static/settings.html`: edit name, home type, occupants, plan and fixed rate; shows the modelled day as a chart |
| [CSDT4-49](https://csd-t4.atlassian.net/browse/CSDT4-49) Simulated population | 100 deterministic households (V8) with `simulated = true`, hidden from `GET /api/v1/households` unless `?includeSimulated=true` |
| [CSDT4-50](https://csd-t4.atlassian.net/browse/CSDT4-50) "About our data" page | `static/about-data.html` |

Still open because they depend on other features:

- "Switching to fixed rate stops new shifting suggestions": F4 must read `exposedToWholesalePrice`.
- "Load profile includes accepted runs": needs F4's accepted recommendations. Today each run is
  placed at the appliance's usual start.
- "Simulated households can't log in": needs login (CSDT4-23).

## Try it

```sh
docker compose up -d postgres
./mvnw spring-boot:run
```

- http://localhost:8080/appliances.html (add `?household=3` for another household)
- http://localhost:8080/settings.html
- http://localhost:8080/about-data.html
- http://localhost:8080/swagger-ui.html → the "F2" sections

## Units and rules

- Power is **kW**, run time is **minutes**, energy per run (`energyPerRunKwh`) = kW × minutes ÷ 60.
- Times of day are Singapore wall-clock `HH:mm`.
- `FLEXIBLE` needs `earliestStart` and `mustFinishBy`. The window must be at least as long as the
  run. The same start and end means "any time". An end earlier than the start crosses midnight
  (22:00 → 07:00). An optional `usualStart` must leave time to finish inside the window.
- `FIXED` needs `usualStart` (the time it always starts) and no window.
- `NOT_FLEXIBLE` has no times; its energy is spread over the day.
- Validation errors are HTTP 400 with `"errors": {"field": "message"}`.

## How the modelled load works (CSDT4-29)

1. Start from EMA's 2024 average monthly use for the home type (MSE written reply to a
   parliamentary question, 24 Sep 2025): 1–2 room 175.7, 3-room 276.1, 4-room 380.7,
   5-room/Executive 464.0, condo 522.0, landed 1,208.2 kWh.
2. Subtract the monthly energy of the listed appliances, except EV chargers. EVs are not in
   EMA's averages, so they are added on top and reported separately.
3. Share what is left between the days of the month (a weekend day = 1.10 × a weekday), then
   across 48 half-hours using `DailyLoadShape`, an assumed Singapore pattern: aircon at night,
   a morning bump, a quiet weekday afternoon and an evening peak.
4. Place each appliance at its usual start (or its earliest start). A run 3 times a week counts
   as 3/7 of a run every day, i.e. the expected load.

Summed over a month, base load plus non-EV appliances equals EMA's figure exactly
(`LoadProfileCalculatorTest`). The daily shape and the weekend factor are assumptions.

## For F4 (recommendations and spend)

Depend on `sg.edu.smu.cs203.household.HouseholdDirectory`, not on F2's repositories:

- `household(id)` → plan type and `exposedToWholesalePrice` (skip fixed-rate households)
- `flexibleAppliances(id)` → enabled `FLEXIBLE` appliances with their windows
- `loadProfile(id, date)` → kWh per half-hour, for month-to-date spend
- `TimeWindow` (appliance package) answers "can this run start at this time?", including
  windows that cross midnight.

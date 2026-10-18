# F4 — Recommendations (CSDT4-19, CSDT4-20)

Both stories are stretch goals for the midterm demo. This page records what was built, one
design decision the team should know about, and how to switch to F3's real forecast later.

## Decision: where the price forecast comes from (30 Sep 2026)

**Chosen: a small forecast interface with a real and a demo implementation, switched by config.**

| Setting `RECOMMENDATION_FORECAST_SOURCE` | What recommendations use |
| --- | --- |
| `demo` (default) | The real half-hourly USEP of F5's replay day (26 Sep 2026), repeated by time of day. Always available. Every suggestion is labelled **Demo forecast**. |
| `f3` | F3's latest saved forecast (`ForecastService.latest()`), **only** when F3 marks it `actionable`. Otherwise no suggestions, and the page says why. |

Why not the other two options:

- **F3's forecast only** would show no suggestions today. With the default settings F3's forecast
  is never actionable (`period-mapping-evidence` is empty). Even when it is, F3 falls back to its
  B1 baseline while no AI model is approved, and B1 predicts the same price for all 24
  half-hours. With a flat forecast every start costs the same, so there is nothing to suggest.
  F3 also only looks 12 hours ahead.
- **A fake forecast only** would work for the demo but not use F3 at all, so someone would have
  to rewire it next sprint.

CSDT4-19 says "a fake forecast is fine this sprint". The demo curve is real past prices, not
made-up numbers, and the label keeps it from being presented as an AI prediction.

### To switch to F3's real forecast later

1. F3 must be actionable: set `FORECAST_PERIOD_MAPPING_EVIDENCE` as described in
   `docs/forecasting/runbook.md`, and keep the live NEMS ingestion running (a stale feed makes
   the forecast non-actionable).
2. Ask the F3 lead to stop defaulting to the flat B1 baseline (for example, prefer B2 until a
   validation ranking exists). Until then `f3` mode will rarely find a saving.
3. Set `RECOMMENDATION_FORECAST_SOURCE=f3` and restart. No code change is needed.

## What was built

- `PriceForecastSource` is the forecast interface ("the ForecastService interface" in CSDT4-19).
  `DemoPriceForecastSource` and `F3PriceForecastSource` implement it; `PriceForecastConfiguration`
  picks one.
- `CheapestStartCalculator` holds the CSDT4-19 rule. It is plain Java with no Spring or system
  clock, so the tests are exact.
- `RecommendationService`, `RecommendationRepository` and `RecommendationController` provide the
  API. Tables are in `V10__recommendations.sql`.
- A "Suggested times" card on the dashboard (`static/js/recommendations.js`) has Accept,
  Dismiss and "Pick my own time". Decisions update the card in place. Nothing runs
  automatically.

## API

| Endpoint | What it does |
| --- | --- |
| `GET /api/v1/households/{id}/recommendations` | Open (`ACTIVE`) suggestions, best saving first, plus the latest background recompute's outcome. What the pages call. |
| `POST /api/v1/households/{id}/recommendations/generate` | Recomputes one household now. Normally done in the background (CSDT4-37). Safe to repeat. |
| `POST /api/v1/recommendations/{recId}/accept` | Accepts. Optional body `{"startTime": "15:10"}` is the resident's own start (Singapore `HH:mm`) inside the same window. |
| `POST /api/v1/recommendations/{recId}/dismiss` | Dismisses. The same run is not suggested again. |
| `POST /api/v1/recommendations/{recId}/got-it` | "Got it" on a spike warning (CSDT4-38). That run is not warned about again. |
| `POST /api/v1/recommendations/{recId}/remind-later` | "Remind me later" on a spike warning: hidden for an hour, or until an hour before the usual start if sooner. 409 when the usual start is under an hour away. |

Errors: 404 for an unknown household or suggestion, 409 if the suggestion was already decided or
expired, 400 with `errors.startTime` for an override outside the window.

Two `generate` calls for the same household (page load plus Refresh, or two tabs) run one after
the other because of a per-household PostgreSQL advisory lock, so they can't both insert.

## Rules (CSDT4-19)

- Cost of a run = Σ power (kW) × hours in each half-hour × USEP (SGD/MWh) ÷ 1000, in SGD.
  Part half-hours count (a 19:45 start spends 15 minutes in the 19:30 half-hour).
- Only enabled `FLEXIBLE` appliances of price-linked households get suggestions. Fixed-rate
  households get none, and any open ones are closed.
- The run compared is the next one whose usual start is still ahead. If `usualStart` is empty,
  the window's start is used (the same assumption as F2's load model).
- Candidates start on a :00 or :30 boundary, not before now, not before the window opens, and
  must finish by `mustFinishBy`. A 60-minute dryer that must finish by 21:00 can start at 20:00
  at the latest, never 21:30 (`CheapestStartCalculatorTest`).
- A suggestion is saved only if it saves at least `RECOMMENDATION_MINIMUM_SAVING_SGD` (default
  $0.05, CSDT4-37). Ties keep the earlier start.
- If the forecast doesn't cover the whole usual run, nothing is compared. This can happen with
  F3's 12-hour horizon late in the day.

## Tables and statuses

`recommendation` stores kind (`SHIFT_START` or `AVOID`), suggested_start, usual_start,
est_saving, both costs, reason, forecast_source/model, status, accepted_start, decided_at, and for
warnings spike_start and snoozed_until (V13).

`recommendation_event` logs every change: `CREATED`, `ACCEPTED` (with the start and whether it
was an override), `DISMISSED`, `ACKNOWLEDGED`, `SNOOZED` (with the remind time), `SUPERSEDED`
and `EXPIRED`.

Statuses:

- `ACTIVE`: waiting for the resident. At most one per appliance, enforced by a unique index.
- `ACCEPTED` / `DISMISSED`: the resident's decision on a shift suggestion.
- `ACKNOWLEDGED`: "Got it" on a spike warning.
- `SUPERSEDED`: replaced by newer advice, or the appliance changed.
- `EXPIRED`: the suggested start passed without a decision (for a warning, also once the usual
  start it warns about has passed).

Both tables cascade when a household or appliance is deleted (CSDT4-62).

## Guards, ranking and background recompute (CSDT4-37)

Every suggestion passes five guards in `RecommendationService.generate`:

| Guard | Rule |
| --- | --- |
| Time window | The run must start and finish inside the appliance's window, including windows that cross midnight (`CheapestStartCalculator`, following F2's `TimeWindow`) |
| Past start | Never a start before now |
| Disabled appliance | Only enabled `FLEXIBLE` appliances are considered (`HouseholdDirectory.flexibleAppliances`); a switched-off appliance's open suggestion is closed |
| Fixed-rate household | `exposedToWholesalePrice` must be true; switching to a fixed plan closes open suggestions |
| Stale feed | While F1's price is `STALE` (or there is no price yet), no new suggestions from F3's forecast. The labelled demo forecast doesn't use the live feed, so it is only blocked when `RECOMMENDATION_STALE_FEED_BLOCKS_DEMO=true` |

**Ranking.** Each appliance's best shift is ranked by expected saving against its usual start
(ties: earlier start first). Savings under `RECOMMENDATION_MINIMUM_SAVING_SGD` ($0.05) are skipped,
and at most `RECOMMENDATION_MAX_ACTIVE` (3) suggestions per household are open at once. A
suggestion pushed out of the top 3 is closed as `SUPERSEDED`. The list endpoint returns them best
saving first.

**Background recompute, not on page load.** `RecommendationRecomputeJob` recomputes every real
household (simulated ones are skipped) on startup, when F1 saves a new price, every 30 minutes at
:01 and :31 (`RECOMMENDATION_RECOMPUTE_CRON`), and recomputes one household when F2 announces a
change to it or its appliances (`HouseholdChanged`, published by `HouseholdService` and
`ApplianceService`). A new F3 forecast is picked up by the next 30-minute run. The pages only call
`GET /households/{id}/recommendations`, which reads the open suggestions and the latest outcome
saved in `recommendation_refresh` (V12): when it ran, which forecast it used, and why there are no
suggestions. `POST …/generate` still exists for recomputing one household by hand (Swagger).
Set `RECOMMENDATION_AUTO_RECOMPUTE=false` to turn the background job off.

## Price spike warnings (CSDT4-38)

When the forecast marks a spike in any half-hour an appliance's usual run touches, its suggestion
becomes an `AVOID` warning: "avoid your usual time", with the cheapest start in the window that
touches no spike, and the cost avoided by using it (usual cost minus that start's cost).

- **Where spikes come from.** F3 marks each forecast half-hour with `spikeFlag` (above the 28-day
  typical price for that time plus 3 spreads; `forecast.spike.*`). A missing flag (too little
  history) is not a spike. The demo forecast applies the same rule to the 28 days before its
  replay day (26 Sep), using the saved market history, so the demo can show warnings.
  `RECOMMENDATION_DEMO_SPIKES=false` turns that off; with too little history nothing is marked.
- **Same rules as other suggestions.** Warnings are made in the same background recompute, so the
  fixed-rate, stale-feed, disabled-appliance, time-window and past-start guards all apply. Savings
  under $0.05 are skipped, and warnings are ranked with shift suggestions by saving (max 3 open).
- **One card per appliance.** A warning replaces that appliance's shift suggestion. If no
  spike-free start in the window saves at least $0.05, the appliance gets an ordinary suggestion
  (or none) instead.
- **Got it** closes the warning (`ACKNOWLEDGED`); the same run is not warned about or suggested
  again. A run whose shift suggestion was only dismissed can still get a warning, since the
  resident will run at the usual time.
- **Remind me later** keeps the warning open but hidden until `min(now + 1 h, usual start - 1 h)`.
  The button isn't shown once the usual start is under an hour away. A newer warning for the same
  run (e.g. after a forecast update) keeps the reminder time.
- Shift suggestions are answered with Accept / Dismiss, warnings with Got it / Remind me later;
  the wrong pair returns 409.

## Not done here (later stories)

- Richer explanations (CSDT4-39).
- Realised savings from actual prices (CSDT4-53), and feeding accepted runs into F2's load
  profile.
- Login: accept/dismiss are open to anyone until CSDT4-23.

## Try it

```sh
docker compose up -d postgres
./mvnw spring-boot:run
```

Open http://localhost:8080/dashboard.html. The Tan household's dryer, washing machine, water
heater and EV charger should get demo suggestions. The endpoints are under "F4" in
http://localhost:8080/swagger-ui.html.

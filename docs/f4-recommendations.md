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
| `POST /api/v1/households/{id}/recommendations/generate` | Re-checks the forecast and saves a suggestion per flexible appliance where a cheaper start exists. Safe to repeat (the dashboard calls it on load and on Refresh). |
| `GET /api/v1/households/{id}/recommendations` | Open (`ACTIVE`) suggestions without re-checking. |
| `POST /api/v1/recommendations/{recId}/accept` | Accepts. Optional body `{"startTime": "15:10"}` is the resident's own start (Singapore `HH:mm`) inside the same window. |
| `POST /api/v1/recommendations/{recId}/dismiss` | Dismisses. The same run is not suggested again. |

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
  $0.01). Ties keep the earlier start.
- If the forecast doesn't cover the whole usual run, nothing is compared. This can happen with
  F3's 12-hour horizon late in the day.

## Tables and statuses

`recommendation` stores kind (`SHIFT_START`), suggested_start, usual_start, est_saving, both
costs, reason, forecast_source/model, status, accepted_start and decided_at.

`recommendation_event` logs every change: `CREATED`, `ACCEPTED` (with the start and whether it
was an override), `DISMISSED`, `SUPERSEDED` and `EXPIRED`.

Statuses:

- `ACTIVE`: waiting for the resident. At most one per appliance, enforced by a unique index.
- `ACCEPTED` / `DISMISSED`: the resident's decision.
- `SUPERSEDED`: replaced by newer advice, or the appliance changed.
- `EXPIRED`: the suggested start passed without a decision.

Both tables cascade when a household or appliance is deleted (CSDT4-62).

## Not done here (later stories)

- Scheduled generation (it currently runs when the dashboard loads), ranking across appliances
  and household guards (CSDT4-37).
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

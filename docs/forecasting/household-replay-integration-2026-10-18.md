# Household replay integration evidence — 18 October 2026

A genuine F4 implementation exists in the **cached** remote branch
`origin/feature/CSDT4-19-20-recommendations`, commit
`774de935aac5a52c6b5049e2bde7f8d5944fb20c`. It was absent from the current feature
checkout when inspected. This is teammate-owned source to integrate through its
existing contracts, not a reason to invent a second recommendation engine.

## Verified F4 contracts

Primary source: [the immutable F4 commit](https://github.com/CS203-G3-T4/CS203-G3-T4/tree/774de935aac5a52c6b5049e2bde7f8d5944fb20c/src/main/java/sg/edu/smu/cs203/recommendation)
and its [integration notes](https://github.com/CS203-G3-T4/CS203-G3-T4/blob/774de935aac5a52c6b5049e2bde7f8d5944fb20c/docs/f4-recommendations.md).
All source, tests, configuration reference, V10 migration and required replay CSVs
were extracted unchanged under `.local/acceptance-2026-10-18/f4-reference/`.
`source-manifest.json` records each path and SHA-256. No checkout, merge or reset
was performed during this research.

- `PriceForecastSource.forecast()` returns `Lookup(PriceForecast, unavailableReason)`.
  `PriceForecast` contains `source`, `model`, `asOf`, and immutable
  `List<HalfHourPrice(periodStart, usepSgdPerMwh)>`.
- `F3PriceForecastSource` calls `ForecastService.latest()`, requires `actionable`
  and a saved run, then converts its non-null points. Keep this guard.
- `CheapestStartCalculator.cheapestStart(ShiftableRun, List<HalfHourPrice>, Instant now,
  BigDecimal minimumSaving)` is a pure Java entry point. `ShiftableRun` contains
  `powerKw`, `runMinutes`, `earliestStart`, `mustFinishBy`, `usualStart`.
  `Result` contains `outcome`, usual/best start, usual/best cost and saving.
- Outcomes are `SHIFT`, `USUAL_IS_CHEAPEST`, `NOT_ENOUGH_FORECAST`.
  It uses F2's `TimeWindow`, selects the next usual run still ahead of the origin,
  enumerates half-hour candidate starts inside that occurrence, costs partial
  half-hours, handles midnight windows, and rejects runs missing forecast prices.
  Equal costs retain the earlier start; minimum forecast saving defaults to SGD0.01.
- `RecommendationService` depends on `HouseholdDirectory`, `PriceForecastSource`,
  shared `Clock` and its own repository. It excludes fixed-rate households,
  uses enabled flexible appliances and does not automatically operate appliances.

Existing REST paths on that branch:

| Endpoint | Contract |
| --- | --- |
| `GET /api/v1/households/{id}/recommendations` | Active suggestions |
| `POST /api/v1/households/{id}/recommendations/generate` | Generate/recheck, idempotent per-household lock |
| `POST /api/v1/recommendations/{id}/accept` | Accept, optional `{"startTime":"15:10"}` override |
| `POST /api/v1/recommendations/{id}/dismiss` | Resident dismissal |

Those endpoints are not a bulk historical feasible-plan export. `estSavingSgd`
is predicted saving, not realized saving. F4's own notes explicitly leave realized
savings and feeding accepted runs into F2 for later work.

Configuration chooses `demo` or `f3`. The demo repeats actual September 26 prices
and is clearly labelled; it cannot establish unseen AI savings. The extracted
`application.yml` is reference only: merge the three `recommendation` settings,
never replace current application configuration. Default demo additionally needs
the two extracted classpath replay CSVs. Use `f3` for actual application integration.

F4 owns `V10__recommendations.sql`. Cached `origin/main` commit
`d4c663f94a6b7da6dcee59250d022cf370f19e29` has V1–V9; cached dashboard/replay branches
have V1–V8. Thus new forecast operations must use a compatible later migration,
not collide with F4's V10 or edit an already applied migration.

Jira was read only. [CSDT4-19](https://csd-t4.atlassian.net/browse/CSDT4-19) confirms
half-hour feasible-start enumeration and the 21:00-finish regression.
[CSDT4-20](https://csd-t4.atlassian.net/browse/CSDT4-20) preserves resident choice.
[CSDT4-51](https://csd-t4.atlassian.net/browse/CSDT4-51),
[CSDT4-147](https://csd-t4.atlassian.net/browse/CSDT4-147) and
[CSDT4-148](https://csd-t4.atlassian.net/browse/CSDT4-148) require held-out weeks,
same households, F4 replay with AI and best baseline, plus savings/MAE/spike scores
whether AI wins. No Jira writes occurred.

## Genuine F2 hand-off and replay limits

The existing Java seam is `HouseholdDirectory.household(id)`,
`flexibleAppliances(id)`, `loadProfile(id,date)`. Its public REST equivalents are:

- `GET /api/v1/households?includeSimulated=true`, then
  `GET /api/v1/households/{id}` for plan/exposure and `updatedAt`.
- `GET /api/v1/households/{id}/appliances` for power, duration, flexibility,
  windows, usual start, enabled status and runs/week.
- `GET /api/v1/households/{id}/load-profile?date=YYYY-MM-DD` for 48 modelled kWh
  slots, and `GET /api/v1/archetypes` for EMA calibration assumptions.

These are app-owned profiles, not measured customers. V5/V7 demo households and
V8's deterministic population are explicitly modelled. `LoadProfileResponse.modelled`
is always true; run frequency is an expected `runsPerWeek/7` daily fraction.
Cross-midnight placement wraps within F2's modelled daily profile. Do not call
that an observed schedule or double-count the same run at overlapping origins.

Household and appliance database entities have creation/update timestamps but no
historical revision log. `ApplianceResponse` does not export those timestamps.
A current API snapshot therefore cannot establish July 2026 configuration as of
an old forecast origin. For a declared fixed-profile counterfactual study, freeze
and checksum the current F2 export and clearly state that the same modelled
profiles are applied to historical prices. For claims of operational household
replay, obtain historical profile/plan snapshots with genuine effective and
availability times; do not backdate the current profile.

## Required export for auditable replay

Keep existing F2/F4 ownership. The replay adapter needs an immutable, versioned
export carrying:

1. Dataset/source commit, export time/hash; `householdId`, plan/exposure, fixed
   rate if applicable; household/appliance configuration identity and effective,
   publication/availability instants, or an explicit fixed-profile counterfactual
   label when historical configuration is unavailable.
2. For each distinct run: `caseId`, `applianceId`, origin, window occurrence,
   power kW, integer duration minutes, usual start, enabled/flexibility state,
   frequency/consumption modelling assumption and feasibility-owner/version.
3. Identical ordered aware `periodStart` instants for actual prices, saved AI and
   validation-selected baseline forecasts, with model/version/origin identities,
   truth policy and forecast provenance. No retrospective DEMO forecast as AI.
4. `usualPlanKwh` and identical `feasiblePlansKwh` for both methods, or the genuine
   F4 calculator's selected usual/AI/baseline starts and minute-overlap energy
   allocation. Preserve equal energy, contiguous duration and window/deadline
   feasibility. Whole candidate runs need price coverage; skip incomplete ones
   with an explicit reason instead of filling targets.
5. One realized cost per unique scheduled run and actual-price target, preventing
   overlapping forecast origins from counting repeated use. Fixed-rate households
   retain the usual schedule and have zero shifting saving; actual bill-cost
   context uses the fixed tariff, not USEP.

No genuine historical household-config/accepted-run export or measured consumption
was found in the inspected current source, cached F4 source, immutable dataset
manifests or runtime research artifacts. The F4 calculator is available for actual
integration and honest modelled counterfactual savings. Historical configuration
vintages and realized resident adherence remain dependencies for stronger claims.

# Month-to-date spend and budget projection (CSDT4-41, CSDT4-42)

"How much have I spent on electricity so far this month?" on the Spending & Budget page
("Spent so far this month"), from `GET /api/v1/households/{id}/spend/month-to-date`.

## How it is worked out

Every **finished** half-hour from the 1st of the month (Singapore time) up to now:

- **Use:** F2's modelled kWh for that half-hour (`LoadProfileService`). Wattly has no meter data.
- **Price-linked plan:** `kWh × (USEP ÷ 1000 + each per-kWh charge that applies then)`, plus each
  monthly charge × (half-hours so far ÷ half-hours in the month), then `× 1.09` for 9% GST.
  USEP is F1's saved price for that half-hour as known at the clock's time, in SGD/MWh, so
  `kWh × USEP ÷ 1000` is SGD.
- **Fixed-rate plan:** `kWh × the contracted rate ÷ 100` (¢/kWh as entered in Household Settings;
  no market price needed and no charges added, since the contracted rate is what the plan charges).
- **A half-hour with no saved market price** is left out and counted (`halfHoursWithoutPrice`);
  the page says e.g. "855 of 860 half-hours priced". Nothing is guessed or filled in.
- **"This month" and "now" come from the injected `Clock`**, so when F5 supplies a demo clock the
  figure follows it.

## The other charges (`tariff_component`, V16)

Rates exclude GST and record their source. For a low-tension household buying at the wholesale
electricity price through SP Group:

| Code | Charge | Rate | When | Source |
| --- | --- | --- | --- | --- |
| `TRANSMISSION_PEAK` | Transmission charge | 6.1000¢/kWh | 7am-11pm | SP Group WEP fact sheet, 1 Jul 2026 |
| `TRANSMISSION_OFF_PEAK` | Transmission charge | 4.7800¢/kWh | 11pm-7am | same |
| `MARKET_DEVELOPMENT_SYSTEMS` | Market Development and Systems Charge | 0.1900¢/kWh | all day | same |
| `RETAIL_SETTLEMENT_UPLIFT` | Retail Settlement Uplift | 0.1471¢/kWh | all day | same |
| `METER_READING` | Meter Reading and Data Management | S$2.18/month | prorated | same |
| `GST` | GST | 9% | on the subtotal | same |

- Fact sheet: [SP Group, buying at the Wholesale Electricity Price (version 1 Jul 2026)](https://www.spgroup.com.sg/dam/jcr:3f430f5e-14d5-4d05-bb6a-a3cd7cc6d1f4/Fact%20Sheet%20for%20WEP_1Jul2026_w%20customer%20advisory_web.pdf)
- Peak (7am-11pm) and off-peak (11pm-7am) hours: [SP PowerAssets Transmission Service Rate Schedule, notes 2.2](https://www.spgroup.com.sg/dam/jcr:aa566693-96a3-4eac-bead-b4bbe4bb95ee/Transmission%20Service%20Rate%20Schedule%20(Applicable%20with%20effect%20from%201%20Apr%202025).pdf)
- **Not included:** the vesting contract debit/credit (variable, not published per household).

**Updating rates:** when SP Group publishes a new fact sheet, add rows with the new
`effective_from` in a new migration; don't edit old rows. The calculator uses, for each day, the
latest version in force on that day, so a month that spans a change is still right.

## Worked example (`SpendCalculatorTest`)

1 Oct 2026 (October has 1,488 half-hours): 06:30 off-peak 0.5 kWh at 200 SGD/MWh, 07:00 peak 1.0 kWh
at 300 SGD/MWh, 07:30 0.4 kWh with no price (skipped).

| Part | Working | SGD |
| --- | --- | --- |
| Energy | 0.5 × 200/1000 + 1.0 × 300/1000 | 0.4000000 |
| Transmission | 0.5 × 0.0478 + 1.0 × 0.061 | 0.0849000 |
| MDSC | 1.5 × 0.0019 | 0.0028500 |
| Retail settlement | 1.5 × 0.001471 | 0.0022065 |
| Meter | 2.18 × 3/1488 | 0.0043952 |
| Subtotal | | 0.4943517 |
| GST 9% | | 0.0444916 |
| **Total** | | **0.5388433 → S$0.54** |

## Budget projection and over-budget alert (CSDT4-42)

One shared projection, `GET /api/v1/households/{id}/budget-status` (`BudgetStatusService`), used by
both the Dashboard and the Spending page so they always show the same figures:

    projected = spent so far + average daily spend over the last 7 full days × days left

- **Spent so far** is the month-to-date figure above.
- **Last 7 full days** are the 7 days before today (Singapore time), so in the first week of a
  month they include last month's days. Each day is worked out exactly like "spent so far"
  (`SpendService.dailySpend`). A day with any half-hour missing a price is left out of the average
  (`daysInAverage` says how many counted); with none left, there is no projection and `message`
  says why.
- **Days left** is the time from now to the end of the month, in days (e.g. 21.5 at noon on the
  10th of a 31-day month).
- **Alert** is shown when the projection is over the monthly budget and alerts are on.

`PUT /api/v1/households/{id}/budget` saves `monthlyBudgetSgd` and `alertsEnabled` (V17
`household_budget.alerts_enabled`, on by default; leaving it out keeps the saved choice). The
planning rate is still accepted but the pages no longer use it, so its box was removed.

Pages (`static/js/budget-status.js` formats the figures and banner for both):

- **Spending page:** the over-budget banner; "Projected this month" with how it was worked out
  ("S$5.00/day over the last 7 days × 21.5 days left"); the budget box ("Projected S$7.50 over");
  a daily chart of actual spend so far, then the 7-day average for the remaining days; and the
  budget form with a "Warn me…" checkbox.
- **Dashboard:** the same banner, and one line with the same figures ("This month: S$50.00 spent
  so far · projected S$157.50 · budget S$150.00").

Worked example (`BudgetStatusServiceTest`, fixed Clock at noon on 10 Oct 2026): S$50.00 spent so
far; 3-9 Oct cost 4+4+5+5+6+6+5 = S$35, an average of S$5.00/day; 21.5 days left; projection
50 + 5 × 21.5 = **S$157.50**. Against a S$150 budget that is S$7.50 over, so the banner shows if
alerts are on.

`GET /api/v1/households/{id}/budget` (the earlier planning-rate estimate) is unchanged for
compatibility; the pages use `budget-status` instead.

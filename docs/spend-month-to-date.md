# Month-to-date spend (CSDT4-41)

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

## The other charges (`tariff_component`, V17)

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

## Notes for the Spending page

"Spent so far" and its breakdown come from this endpoint (`static/js/spend.js`). The projection,
daily chart and budget comparison are still the planning-rate estimate from `BudgetService`.

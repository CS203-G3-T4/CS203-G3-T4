# F3 report: current price-only forecasting

Updated 1 November 2026 for CSDT4-51. This report records current application
behaviour and the completed evaluation. It does not assert team approval,
external submission, production acceptance or measured household-bill savings.

## Current application

The local app serves `price-only-183d-20261031-local-v1`: 24 trained
gradient-boosted pipelines using completed prices, calendar and public holidays.
The final fit uses 183 days / 8,760 origins before 29 July 2026. Solar and
expected-demand inputs are omitted exactly as fitted. Serving is experimental,
loopback-only, checksum-pinned and `productionEligible=false`. Java validates,
saves forecasts and provides baseline fallback when AI is unavailable.

The separate `price-only-live-20261031-v1` comparison runs from 31 October 2026
16:48:09 to 28 November 2026 16:48:09 SGT. Its model and original S$0.05 policy
remain frozen. Earlier solar-trial records remain separate. Weekly retraining
stays inactive; model-specific savings ranges need 20 complete eligible days.
[Serving and trial details](forecasting/price-only-ai-2026-10-31.md).

## Price error and spike warnings

Same eight previously examined weeks, 1 June–26 July 2026: 2,496 forecast
origins and 59,904 paired predictions across 24 horizons. Each weekly fit ends
before its evaluation week. The final July bundle is not used to predict its
own training dates; these results assess the rolling recipe. B3 averages the
same target half-hour over the preceding seven days and was selected earlier.

| Mean absolute error | Price-only AI, cents/kWh | B3, cents/kWh |
|---|---:|---:|
| All prices | 5.2851 | 5.9333 |
| Ordinary prices | 3.0734 | 3.8502 |
| Spike prices | 13.5669 | 13.7335 |

Overall RMSE is 115.3032 vs 120.7325 SGD/MWh. Ordinary-price MAE improves
20.2%; spike-price MAE improves only 1.2%. Overall MAE is lower in all eight
development weeks, which does not imply better scheduling each week.

At fixed three-hour lead, AI warns before 38/113 complete statistical spike
episodes, versus B3's 39/113. AI has 194 false-warning half-hours in 77 runs,
versus 312 in 111 runs. Half-hour precision is 52.8% vs 38.6%; recall is 41.6%
vs 37.5%. These are past-only, same-half-hour price-anomaly labels, overlapping
forecast pairs and raw model warnings, not independent genuine-event truth or
delivered personal alerts. The 20% personal false-warning target is unproved.

## Appliance costs and fixed safety comparison

Same 600 simulated scenarios, original starts, feasible windows, power,
duration, actual prices and refresh opportunities. The unchanged Java calculator
uses own-model forecast flags, up to three suggestions per household/refresh
and first simulated acceptance. Money is frequency weighted by runsPerWeek/7;
counts are unweighted scenarios, not 600 real customers.

| Policy | Net savings SGD | Costlier moves / moves | Harmful loss SGD |
|---|---:|---:|---:|
| Current AI, S$0.05 | 34.0884 | 104 / 446 | 9.8119 |
| AI, S$0.10 | 29.4200 | 69 / 305 | 10.0004 |
| AI, S$0.05 + agreement | 34.0390 | 97 / 427 | 9.3460 |
| AI, S$0.10 + agreement | 29.5123 | 62 / 290 | 9.2642 |
| B3, S$0.05 | 31.0476 | 95 / 431 | 12.3648 |
| B3, S$0.10 | 27.0317 | 57 / 292 | 10.6000 |

The higher floor is passed into the calculator before selecting an alternative.
Agreement requires B3 to predict positive savings at the same AI-proposed start;
actual prices never enter that decision. Filtering precedes household ranking.
The six policies were fixed before replay; no extra thresholds or models were
searched. None of these experimental policies is applied to the live trial.

The S$0.10 floor reduces bad-move count but sacrifices savings and alone slightly
increases harmful loss. Agreement-only preserves about 99.86% of current
savings with seven fewer harmful cases net. It prevents nine original harmful
cases but introduces two others: rejecting early advice can lead to a later
acceptance. Current/agreement-only AI win four weeks and lose four against B3;
combined S$0.10 AI wins three and loses five against same-floor B3. No policy
establishes consistent superiority, so this bounded experiment ends here.

Earlier solar/blend savings of S$37.2051/S$27.1044 are separate results and are
not attributed to price-only AI. Original evidence remains in the
[earlier report record](forecasting/evidence/2026-10-29-delivery/candidate-comparison.json).

## Validation and remaining work

Actual main `ee11c05` (merged PR40) is integrated without conflicts or source
file changes. Replay migrations V23-V25 precede trial V26 and diagnostics V27.
Combined-source checks passed: 427 Java/PostgreSQL tests with no failures,
errors or skips; 181 Python tests; 82 JavaScript tests; and the unchanged
37-file frozen archive rebuild/verification. A disposable test database was
removed. No new browser run is claimed because this update changes no UI.

The replay reproduced all 24,128 original control refresh decisions and checked
3,600 final decisions using exact rational time-overlap costs. A parameterised
reproduction matched all six summaries. Detailed research scripts, outputs,
private scenario inputs, raw datasets and model bundles stay outside Git.

Historical final-price availability and household adherence are assumptions.
These repeatedly examined dates are development evidence, not untouched
confirmation or measured bills. Continue the frozen prospective comparison;
do not tune against it. No model, live policy, database history, collector or
timer was changed by this update.

CSDT4-35 still specifies expected demand; its source/scope decision awaits the
owner's consultation with Jonathan. Subtask 100 remains Done on feature-pipeline
implementation, not real demand-aware accuracy. CSDT4-33 still needs agreement
on genuine-spike definition and team sensitivity. Completed implementation
subtasks and task 51's reporting status are unchanged. Independent latest-head
review and current GitHub CI remain PR checks, not claims made by this report.

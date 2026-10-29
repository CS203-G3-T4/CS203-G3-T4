# F3 final report: forecasting and appliance recommendations

Prepared 29 October 2026 for the project submission (CSDT4-51). This is the F3 report section to include in the team's final submission; it is not a claim that an external submission has occurred.

The project implements trained AI forecasting, three statistical baselines, chronological evaluation, saved forecasts, Java fallback, appliance recommendations and an admin accuracy view. The AI does not consistently outperform the baseline on household savings. Advance spike prediction remains unreliable. No research model is approved or deployed by this report.

## Identical-case savings comparison

The final fixed comparison uses 600 counterfactual appliance runs across eight previously examined weeks, 1 June–26 July 2026. Every method uses the same original start, power, duration, feasible windows, actual prices, frequency weights and refresh opportunities. The unchanged Java F4 calculator uses its S$0.05 minimum predicted saving and top-three household ranking. A first simulated acceptance locks the selected start. One fixed-rate household receives no wholesale timing advice.

B3 (the same target half-hour averaged across the previous seven days) was chosen using only earlier common-pair MAE. Both AI candidates retain the same saved solar warning flags: this isolates the numerical forecast change. There is no new training, threshold search or tuning on these outcomes.

| Forecast | Simulated savings SGD | Harmful moves | Total harmful loss SGD | False notification episodes | Weekly wins/losses vs B3 |
|---|---:|---:|---:|---:|---:|
| Saved solar-mean AI | 37.2051 | 97 | 7.6748 | 141 | 4 / 4 |
| Saved raw-score blend AI | 27.1044 | 106 | 15.4309 | 144 | 3 / 5 |
| B3 | 31.0476 | 95 | 12.3648 | 146 | reference |

Savings/loss totals are frequency weighted (`runsPerWeek/7`); counts are unweighted scenario runs, not independent customers or market events. The mean AI's +S$6.1575 advantage is driven by one week's +S$7.4042; the other seven weeks net negative. The blend loses S$3.9431 to B3 despite lower price error. Retain the existing recommendation behavior and the mean as the research reference. This is not a prospective model-selection success or permission to promote a bundle.

## Price error and spike evidence

On the same 59,904 forecast pairs, mean AI / blend AI / B3 overall MAE is 52.0227 / 50.0453 / 59.3331 SGD/MWh; ordinary-price MAE is 30.0956 / 24.1927 / 38.5020. Lower MAE does not establish better household scheduling.

The preceding unchanged-policy study provides comparable pair-level spike diagnostics for mean AI and B3: true positives 5,145 / 4,621; false positives 4,141 / 7,617; false negatives 7,481 / 8,005. Precision is 55.4% / 37.8%; recall 40.7% / 36.6%. These are repeated forecast-pair outcomes, not independent genuine events or delivered alerts. The blend retains mean warnings in the final comparison, so that component is unchanged.

A separate costly-spike research policy warns 13 of 28 complete costly events early, with 786 false-warning origins grouped into 199 episodes. This is a different label/cohort from pair precision and must not be combined with it. No independent genuine-scarcity labels or team-approved sensitivity are established.

In the bounded appliance-policy study, existing mean-AI recommendations warn 20 of 70 exposed canonical episodes in time to act. The tested conservative rule reduces false notifications from 141 to 12, but savings collapse from S$37.2051 to S$4.2485 and advance detections from 20 to 4; its worst loss increases. Do not adopt it. Appliance overlap already exists in F4 and produces identical decisions.

## Architecture and readiness

Python/FastAPI is the selected runtime behind the existing ForecastService contract. Java retains validation, persistence, ranking and fallback. Java/Tribuo training was rejected; ONNX serving is deferred and unimplemented. Those are architectural decisions, not delivered alternative implementations.

Forecast endpoints, 24-point persistence, the dashboard and admin accuracy functionality are merged. A research blend cannot be relabelled as a production bundle: approved serving requires compatible feature/calendar/dependency identities and validated provenance. The 183-day research candidates do not satisfy a 12-month production-training claim.

Expected future demand remains a data dependency. The target/published/available demand contract and feature path are implemented, but current-period archived demand is not a future-target forecast and cannot be substituted. Keep CSDT4-35/100 open until a qualifying input is provided or the team explicitly agrees a scope change.

Weekly retraining preparation is tested in WSL but deliberately inactive at the user's request. Activation requires the persistent app, a dedicated shared-auth ADMIN account, sufficient eligible data and explicit deployment validation. A timer alone is not proof of successful weekly training.

## Limits and next work

Historical prices are revised final values; original publication/receipt vintages for some inputs are unknown. Household seeds, adherence and frequency are counterfactual. These dates have been repeatedly examined, so results are development evidence, not fresh validation or measured bill savings. The final comparison has ended; do not automatically train or search further candidates.

Preserve timestamped raw inputs and forecasts on genuinely later dates, freeze the candidate before those outcomes, and report missing periods, fallback use, lead times, harmful moves and weekly variability. Existing collector and free-input/shadow timers remain unchanged. Do not open protected/demo periods for tuning.

Evidence: [delivery decision](forecasting/delivery-2026-10-29.md), [compact comparison](forecasting/evidence/2026-10-29-delivery/candidate-comparison.json), [comparison verification](forecasting/evidence/2026-10-29-delivery/candidate-verification.json). Raw datasets, models, full replay calls and private configuration stay outside Git.
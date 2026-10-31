# F3 final report: price forecasting and appliance recommendations

Updated 1 November 2026 for CSDT4-51. This supersedes the current-model narrative
of the 29 October report. Earlier solar/blend results remain separately
identified below and in the original immutable evidence. This document does not
assert external submission, independent human approval, model promotion or PR
merge. Task 51's owner-accepted reporting completion remains separate from model
acceptance and measured user benefit.

## Current implementation and remaining acceptance

The local app serves `price-only-183d-20261031-local-v1`: 24 gradient-boosted
pipelines using completed-price history, calendar and public holidays. Its final
fit uses 183 days / 8,760 origins before 29 July 2026. It uses neither solar nor
expected-demand values. Serving is explicitly experimental, loopback-only,
checksum-pinned and `productionEligible=false`, with automatic baseline fallback.
Python/FastAPI runs behind the Java ForecastService contract; Java retains
validation, persistence, recommendation ranking and fallback. Java/Tribuo was
rejected as a runtime option; ONNX is deferred, not delivered.

The model improves ordinary-price error in the examined data, but spike onset
prediction and consistent appliance savings remain weak. CSDT4-35 explicitly
requires expected electricity demand, so price-only serving does not complete
that original criterion. Demand-pipeline subtask 100 is Done on its implemented
and tested feature-contract scope; real demand-aware fitting/evaluation or an
explicit team-approved scope change remains a parent-story decision. No scope
change or Jira transition is made by this report. Solar itself is not named in
the original story's acceptance criteria. The owner will consult Jonathan about
the demand/source decision; this update does not activate another feed.

## Chronological price comparison

The same eight already examined weeks, 1 June–26 July 2026, provide 2,496 origins
and 59,904 forecast pairs across the next 24 half-hours. Each weekly PRICE_183D
fit has all training targets before its test week. The final July bundle is not
used to predict its own June-July training data. Results assess the rolling
recipe, not an unchanged serving artifact. B3 is the same target half-hour
averaged over the previous seven days, selected on an earlier common-pair period.

| Mean absolute error | Price-only AI SGD/MWh | B3 SGD/MWh | AI cents/kWh | B3 cents/kWh |
|---|---:|---:|---:|---:|
| All prices | 52.8511 | 59.3331 | 5.2851 | 5.9333 |
| Ordinary prices | 30.7338 | 38.5020 | 3.0734 | 3.8502 |
| Spike prices | 135.6694 | 137.3349 | 13.5669 | 13.7335 |

AI reduces overall MAE about 10.9%, ordinary MAE 20.2%, and spike MAE only 1.2%.
Overall RMSE is 115.3032 vs 120.7325 SGD/MWh. Overall MAE is lower in each of the
eight development weeks, which does not imply higher household savings each
week. Ordinary/spike partitions use identical origin-frozen, past-only
same-half-hour thresholds: 47,278 ordinary and 12,626 spike forecast pairs.
Overlapping forecast pairs are dependent; these are statistical anomaly labels,
not independent genuine-event labels. Horizon-specific results are in the linked
aggregate JSON. B1/B2/B3 implementation and the earlier all-baseline backtest
remain separate evidence; this update's matched scheduling control is B3.

For a price-linked household using 1 kWh, the ordinary-price MAE translates to
about 3.07 cents of single-period energy-cost estimation error, not a confidence
range or a guaranteed saving. Errors between alternative start times may cancel
or compound. Flat-rate households do not obtain wholesale timing savings.

## Spike warnings

At fixed nominal three-hour lead, price-only AI warns before 38 of 113 complete
statistical spike episodes, versus 39 for B3. AI detects four only after onset
and misses 71 entirely; B3 detects one late and misses 73. One of 114 observed
positive runs is censored and excluded from the event denominator. AI has 194
false-warning half-hours grouped into 77 runs, versus B3's 312 half-hours in 111
runs. Half-hour precision is 52.8% vs 38.6%; recall is 41.6% vs 37.5%. An event
contains adjacent positive periods: a warning targeting a later period can still
precede onset. Event and half-hour denominators must not be interchanged.

AI reduces nuisance model warnings but does not improve advance event coverage
here. About 47.2% of its three-hour half-hour warnings are false under the
statistical label. These are raw model warnings, not delivered personal alerts
after appliance relevance and savings filtering. They cannot establish the
owner's proposed 20% false-personal-warning target.

The owner's six-date chart batch is recorded: 29 SPIKE, 188 NORMAL and 71 UNCERTAIN
out of 288 half-hours. On 217 definite labels, the observed-price k=3 rule has
12 true positives, 16 false positives, 17 misses and 172 true negatives: 42.9%
precision and 41.4% recall. This is a convenience subset with some feedback-seen
labels, not wholly blind validation or an evaluation of the AI's forecast flags.
Agreement on the genuine price-anomaly definition and team sensitivity remains
under CSDT4-33. Original owner labels/reasons remain private; this does not reopen
completed implementation subtasks or silently change k.

## Identical-case appliance savings

Same 600 counterfactual runs, households, original starts, windows, power,
duration, actual prices, frequency weights and refresh opportunities. The
unchanged Java F4 calculator uses S$0.05 minimum predicted saving and up to three
suggestions per household/refresh. First simulated acceptance locks the plan.
Each model derives flags from its own predicted prices and shared thresholds.
No solar warning is borrowed by price-only AI. A fixed-rate seed receives no
wholesale timing advice. These are scenarios, not 600 real customers.

| Forecast | Weighted savings SGD | Moves | Costlier moves | Weighted harmful loss SGD | False/misleading advice cases |
|---|---:|---:|---:|---:|---:|
| Current price-only recipe | 34.0884 | 446 | 104 (23.3%) | 9.8119 | 141 |
| B3 | 31.0476 | 431 | 95 (22.0%) | 12.3648 | 146 |

Savings/loss money is weighted by runsPerWeek/7; counts are unweighted. AI has
S$3.0409 more total simulated savings, or 9.8% more than B3's savings, not 9.8%
off an electricity bill. It wins four weeks and loses four. Its last-week
advantage is S$5.8173; the other seven weeks together lose S$2.7764 to B3. Worst
individual loss is S$1.3022 vs B3's S$1.9533. This does not establish consistent
superiority or a lower probability of a harmful move.

## Fixed recommendation-safety experiment

The 1 November study tests six prespecified policies on the same forecasts and
12,064 refresh opportunities. A S$0.10 floor is applied inside the unchanged Java
calculator. The agreement check withholds an AI move when B3 predicts no saving
at that same AI-proposed start. Actuals are used only for scoring. There is no
threshold grid or new training, and no policy is applied to the live trial.

| Policy | Weighted savings SGD | Costlier moves | Weighted harmful loss SGD |
|---|---:|---:|---:|
| AI S$0.05, current | 34.0884 | 104 | 9.8119 |
| AI S$0.10 | 29.4200 | 69 | 10.0004 |
| AI S$0.05 + agreement | 34.0390 | 97 | 9.3460 |
| AI S$0.10 + agreement | 29.5123 | 62 | 9.2642 |
| B3 S$0.05 | 31.0476 | 95 | 12.3648 |
| B3 S$0.10 | 27.0317 | 57 | 10.6000 |

The higher floor reduces bad-move count but gives up savings and, alone,
slightly increases harmful losses. Agreement-only preserves about 99.86% of
current savings with seven fewer harmful cases net; it remains a modest
development tradeoff, winning only four of eight weeks against B3. The combined
AI policy wins three weeks and loses five against B3 at the same S$0.10 floor.
Withholding can delay acceptance and introduce new harmful cases: it is not
equivalent to simply deleting existing bad advice. Do not infer safe or
consistent recommendations from any of these results. The fixed experiment
ends here without another search or automatic adoption.

All 24,128 existing control refresh decisions reproduce exactly. Exact rational
time-overlap accounting checks all 3,600 final decisions across six policies.
The parameterised replay CLI reproduces all six summaries, with checked source
hashes and identical feasible starts. Private datasets remain outside Git.

## Earlier solar and blend evidence

The 29 October mean-solar / blend / B3 comparison produced S$37.2051 / S$27.1044 /
S$31.0476. The solar mean wins four of eight weeks; its gain is concentrated in
one week. The blend loses money relative to B3 despite lower price error. Those
earlier AI candidates share saved solar flags to isolate numerical forecast
changes; that protocol differs from the current price-only own-flag comparison.
Neither solar's S$37.2051 nor the blend's lower MAE is attributed to the live
price-only model. Earlier studies and rejected conservative policies remain in
the original [delivery evidence](forecasting/evidence/2026-10-29-delivery/candidate-comparison.json).

## Live trial, integration and limits

The separate `price-only-live-20261031-v1` trial runs from 31 October 2026
16:48:09 to 28 November 2026 16:48:09 SGT. Its fixed model/policy pins remain
unchanged. The original solar trial and records remain separate. Results are
simulated wholesale costs assuming plans followed, not metered household bills.
Savings ranges require the configured 20 complete days of eligible evidence;
they are unavailable before then. More collected data does not automatically
retrain or improve the frozen model. Weekly retraining remains preparation-only
and inactive, as requested.

PR40 is merged. PR48 has been updated with actual main ee11c05 in an isolated
worktree; that merge changes no source files relative to the preceding PR48
head. Real replay migrations remain V23-V25, followed by trial V26 and capture
diagnostics V27. No deployed schema, Spring JAR, model or trial was changed by
this report. Restored-copy upgrade/restore evidence remains applicable to the
unchanged SQL; combined-head checks are recorded separately in PR48.

Historical final prices and period-end availability assumptions do not reproduce
original receipt vintages. The dates have been repeatedly examined, so these
are development results, not untouched confirmation. Missing periods, fallback
use, model identity, harmful moves and weekly variation must be reported on later
data. Do not tune the frozen trial or open protected/demo periods for selection.
Demand-source/scope and team spike acceptance remain unresolved decisions.

Evidence: [price-only activation](forecasting/price-only-ai-2026-10-31.md),
[safety method and reproduction](forecasting/price-only-safety-2026-11-01.md),
[aggregate current-model scoreboard](forecasting/evidence/2026-11-01-price-only/comparison.json).

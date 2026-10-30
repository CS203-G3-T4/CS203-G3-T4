# Solar AI/B3 trial review follow-through

Review reference: PR #42 at 649ae82. This source change addresses the concrete review findings. The running app, private database history, frozen model, trial dates and weekly timer have not been changed.

## Purpose, pins and outcomes

The primary runtime purpose is to record a prospective local solar-AI-versus-B3 comparison and show paired savings evidence. Trial `solar-live-20261030-v1` began **30 October 2026 11:48:01 SGT** and ends **27 November 2026 11:48:01 SGT**. Its model is `solar-183d-20261026-local-v1`, SHA-256 `e3d96c2dfb4a49a7698e3f9898bac0a896d90bedec8bd0543e637c116c2fe51d`. Runtime defaults remain disabled outside the explicitly activated local instance.

Operational completion means closing the fixed capture window, grading the matched plans whose actual prices are available, and reporting pending/excluded cases and coverage without silently dropping losses. The desired economic outcome is greater matched modelled wholesale savings than B3 without increased harmful-reschedule count or weighted loss. Price MAE/RMSE, ordinary/spike errors and every weekly savings result must accompany the totals. An aggregate advantage dominated by one week is not a claim of consistent improvement. No metered bill savings or independent spike-event accuracy is established by these paired rows.

**Registration limit:** the original activation protocol did not register a numerical binary superiority gate. This review cannot retroactively turn those economic expectations into a preregistered confirmatory test. Current results remain observational/descriptive. Agree a versioned acceptance rule before a genuinely fresh confirmation if a binary adoption decision is required; insufficient coverage or independent spike support is inconclusive. There is no automatic pass, model promotion or change to the in-flight policy. This fact must be stated in the PR rather than hidden behind a post-start threshold.

## Concrete fixes

- Trial DDL is now **V24**, reserving V23 for PR #40. A disposable PostgreSQL test applies the pinned PR40 V23 fixture and this V24 together, preserves existing data and validates history. Deployment order and the already-applied private-local V23 caveat are in [the trial runbook](../live-comparison-and-savings-alerts.md#migration-coordination). No applied history was repaired or rewritten.
- Overlapping completed solar values must agree. Conflicting valid values raise explicitly; a malformed overlapping value raises instead of deleting a prior valid value. Equal overlaps remain accepted. Invalid non-overlapping values remain gaps and required gaps reject inference. These checks fail closed; they may increase fallback coverage if provider panels disagree. They have not been deployed into the ongoing trial.
- Demand import uses one injected `Clock` instant for both validation and persistence. Repeated identical import failures warn at most once per five minutes, while changed failures and recovery are visible. Polling behavior and the ten-minute receipt limit are unchanged.
- Test-count claims are revision-specific: 383 Java / 144 full Python refer to the earlier implementation; 41 focused Python covered the later feature-acceptance addition. The current migration/clock/logging repair passes **386 Java/PostgreSQL tests**, zero failures/errors/skips. Focused solar/demand Python: **35 passed**. The new combined-head full-suite result is recorded separately when available; counts from different revisions are not presented as one run.

## UI evidence

These are screenshots of actual page source. Forecast/admin states use isolated synthetic API responses solely for UI verification; no synthetic calibration, prices or savings were installed in the running database. The resident warm-up image is the actual local fixture-resident page captured on 30 October. These pictures do not establish model accuracy or savings.

| View | Evidence |
| --- | --- |
| Forecast loading | [Screenshot](review-ui-20261030/forecast-loading.png) |
| Forecast empty | [Screenshot](review-ui-20261030/forecast-empty.png) |
| Forecast error | [Screenshot](review-ui-20261030/forecast-error.png) |
| Forecast stale | [Screenshot](review-ui-20261030/forecast-stale.png) |
| Forecast populated | [Screenshot](review-ui-20261030/forecast-fresh.png) |
| Admin loading | [Screenshot](review-ui-20261030/admin-loading.png) |
| Admin inactive/empty | [Screenshot](review-ui-20261030/admin-empty.png) |
| Admin error | [Screenshot](review-ui-20261030/admin-error.png) |
| Admin pending outcomes | [Screenshot](review-ui-20261030/admin-pending.png) |
| Actual resident range warm-up | [Screenshot](review-ui-20261030/resident-live-range-warmup.png) |

## Clock disclosure

Java business-time behavior uses the injected shared Clock, including the new warning throttling. `collector-live-feed.py` uses actual system UTC to exclude not-yet-observed source/receipt records from its read-only LIVE bridge; it is not a replay adapter. `package-solar-research-model.py` records actual packaging time as a conservative availability bound, not a new model fit time. These operator-script calls remain explicitly disclosed, not claimed to have adopted the Java business Clock. Shared-Clock feature-lead acknowledgement is not asserted.

## Scope and collaboration

The trial DB, matched-plan capture and savings-range/alert UI are related runtime pieces: their saved source identity, plan provenance and residuals must agree. However, the independent spike-label workbook, future demand-candidate experiment and acceptance/research reports do **not** all need to ship to run the solar trial. The review's scope concern is valid. A cleaner split is (1) runtime solar serving + prospective capture/ranges, (2) demand candidate/import work, and (3) spike/research/acceptance evidence. Existing review threads should not be closed merely to rename branches; replacement/split PRs require a deliberate publication decision.

PR #42 remains stacked on #41. Recheck and retarget to main only after #41 merges, and rerun full CI on the resulting exact head. PR #40's reserved V23 must also be accounted for before deploying V24. No teammate's branch was changed, no messages were sent, and no approval or feature-lead sign-off is claimed.

For CSDT4-33, the owner subsequently submitted one partial day: 47 definite labels and one uncertain period. That is not the outstanding full independent validation/team decision. The original labels and reasons stay private and unchanged; they do not turn this source PR into proof of genuine-spike accuracy. CSDT4-35's real demand-aware fit and later unseen comparison also remain outstanding, while its feature-pipeline subtask stays Done.

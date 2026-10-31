# Review regression repairs — 31 October 2026

This increment addresses the three defects found while reviewing PR48 at `fb00952`. It changes neither the fitted model nor the live-trial protocol. It is source delivery, not deployment into the running private trial.

## Provisional truth selection

The trial evaluator and accepted-start monitor previously filtered source/status after `MarketHistoryRepository.asOf` had already reduced each period to one revision. A newer final price or another provider could therefore hide a usable NEMS provisional observation, leaving scores pending or disabling a start-time check.

Both paths now use a source-scoped provisional query before revision selection. Publication and actual-receipt cutoffs, latest eligible revision ordering, equal-time conflict detection and frozen scored outcomes remain enforced. The existing general history method is unchanged for its callers. PostgreSQL regression tests cover newer final/foreign revisions, future publications/receipts, latest eligible provisional selection, missing provisional truth and the current-period savings-alert path. No final or foreign value is substituted when provisional truth is missing.

## Recommendation editing during refresh

The automatic refresh previously checked editing state only before issuing requests. Someone beginning a custom-time edit or decision while the list/chart response was pending could lose that state when the cards were replaced.

Automatic loads now recheck focus, changed time inputs, pending decisions and visibility after the list request and again after the chart request, before rendering. Manual loads retain their existing behavior and request-version protection. Seven new frontend cases cover interleaved editing/decisions at both waits, a failed response and resumption of idle refreshes. Browser checks of the actual page source with isolated synthetic APIs verify preserved edits and subsequent idle updates at desktop and mobile widths, with no page errors or overflow.

Interaction screenshots use only synthetic browser fixtures: [desktop](review-fixes-ui-20261031/editing-preserved-1280.png), [mobile](review-fixes-ui-20261031/editing-preserved-390.png). The existing loading/empty/error/stale layout evidence remains in [the prior UI record](trial-review-response-2026-10-30.md#ui-evidence); this repair changes refresh timing rather than visual layout. No browser check calls the running app or creates resident decisions.

## Saved comparison reproduction

The reproduction helper required the current `PriceForecast.java` to equal the research checkout byte for byte, but the live-trial implementation added provenance fields. That prevented the documented saved-result replay from starting.

The helper now compiles an explicitly frozen copy of the original replay DTO under `scripts/forecast-replay`. Both that fixture and the original research DTO must match SHA-256 `ccf3cccf373a4b045a061b1d869661704824ed7166cc4041a3185ebf3962315f`. Actual calculator and time-window sources must still equal the research versions. The plan and replay adapter now join the before/after source checksums. Regression tests reject changed calculation or frozen contracts while allowing the live DTO's provenance extension.

One bounded replay of the same previously examined 600 cases reproduced all three method reports exactly, including weekly differences, harmful moves and false-notification counts: solar mean S$37.2051, blend S$27.1044 and B3 S$31.0476, with 96,512 scalar cost checks. No fit, parameter search, protected-period evaluation or new accuracy claim occurred. Original datasets and replay outputs remain outside Git.

## Verification and rollout

- Full Java/PostgreSQL suite: **389 passed**, zero failures, errors or skips. Tests used newly created disposable databases; those databases were removed afterwards.
- Full frontend suite: **73 passed**, zero failures or skips.
- Full Python suite: **173 passed**, including five comparison-helper tests; zero failures or skips.
- Real saved comparison: all three full method summaries reproduced exactly.
- Browser interaction: desktop 1280px and mobile 390px passed; synthetic fixture evidence only.

There is no database migration, new dependency, trial reset, model promotion, retraining or service activation. Java uses the supplied Clock for the existing time cutoffs. The fixes are reversible source changes. Preserve the existing PR40 → PR48 merge coordination and the separate, rehearsed migration-history reconciliation for the private instance. Latest-head CI and independent teammate approval remain required.

Compact [verification receipt](evidence/2026-10-31-review-fixes/verification.json). Exact-head CI results are recorded on PR48.

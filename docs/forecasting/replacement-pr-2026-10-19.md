# Replacement F3 PR: current main integration

**Latest update:** PR #20 changed main to `c8ccfda`, adding recommendation V14.
PR #21 now integrates that main and uses forecast **V15**. See
[the merged-tree validation](main-sync-v15-2026-10-19.md) and
[V15 compatibility](migration-v15.md). The 204-test result below is historical
evidence for `195ab04`/`b89bf25`; it is not validation of the new merge.

At reviewed checkpoint `195ab04`, this replacement PR superseded #11 using forecasting parent `2c7fbb4` and then-main `b89bf25`. That main owned V13 recommendation spike warnings, so forecasting used V14. Those V1–V13 and forecast SQL bytes were unchanged. This is historical context; current startup uses [V15 compatibility](migration-v15.md), including legacy forecast V12/V13/V14 decisions.

Main also added the required `HalfHourPrice.spike` JSON field and F4 `AVOID` outcome. The first isolated runtime proof failed HTTP 400 at savings replay. Python now sends only saved origin-known forecast spike flags; truth flags cannot influence the recommendation. Java replay follows both SHIFT and AVOID alternatives. A regression deliberately makes the cheapest period a spike and verifies that replay chooses F4's spike-free alternative instead.

## Historical verification of 195ab04, before PR #20

[Compact machine evidence](evidence/2026-10-19-replacement/verification.json) records this replacement tree separately from [historical V13 verification](review-response-2026-10-19.md).

- Full isolated-stack command: `python scripts/verify-forecast-stack.py` with the dedicated JDK21/Python3.12/PostgreSQL17/browser paths configured as documented in the script and runbook.
- Java: **204 tests, zero failures/errors/skips**, including canonical V13 upgrade and both legacy forecast lineages refusing migration without repairs.
- Python: **58 passed**, 391.65 seconds; one upstream Starlette/AnyIO deprecation warning.
- JavaScript: **15 passed**.
- Spring jar and Python wheel built. Packaged migrations include recommendation V13 and forecast V14, with no obsolete forecast V12/V13 resource.
- Default Spring startup, resident/admin pages, main recommendations/dashboard/budget pages, and actual F2/F4 HTTP replay passed. Archived real collector data keeps its original timestamps; stale inputs remain honestly unavailable. Populated UI fixtures are separately labelled synthetic regression data.
- The temporary native user timer fired. Both scheduled attempts rejected inadequate verified history with exit 2 and persisted FAILED/RETAINED audit rows. This checks safe failure and timer execution; it does not establish permanent weekly training or promotion.
- Rebuilt and verified all 37 frozen research files against archive SHA-256 `4d153531160edf9cb77e160cc1107a37e80434b61585c0f9d689af8531c63551`.

Raw logs/screenshots/private runtime files stay under ignored `.local/new-pr-validation-v2/`. The isolated Spring/PostgreSQL processes and transient timer were stopped. The collector and live database were not changed.

## Modelled savings and remaining decisions

Fresh replay with the current spike-aware F4 contract uses 497 unique appliance scenarios and frozen B3 selection: AI SGD168.987219035942857, B3 SGD176.423356464742857, difference **-SGD7.4361374288** (AI loses SGD7.44 when rounded). Fixed-rate households are not shifted. Missing/out-of-window cases are excluded for both methods. These are counterfactual savings with fixed current project profiles and assumed acceptance, not actual bill savings. Historical reports are preserved without replacing their recorded results.

Independent genuine-spike labels, an agreed false-alarm budget, operational input vintages, production model/auth approval, F1-owner acknowledgement, any legacy database cutover, permanent weekly operation and future confirmation remain explicit. Docker Compose runtime and merged shared-auth sessions were not exercised. GitHub CI on the new PR is a separate check from these local results.

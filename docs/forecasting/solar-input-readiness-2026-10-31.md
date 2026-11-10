# Solar-input readiness: required-period consistency

The experimental solar recipe consumes exactly seven solar periods: the six most recent completed half-hours and the same half-hour yesterday. Its chart adapter previously rejected a snapshot if *any* overlapping historical value differed, including unused periods. A captured public snapshot reproduced this failure while all seven required values agreed with the running frozen recipe.

The adapter now extracts and checks only those seven required observations. Conflicting or malformed observations in that window still reject inference, regardless of panel-processing order. Missing periods, stale/future receipts, bad source URLs, panel shape, period mapping and current/future dispatch exclusions retain their existing checks. There is no rounding tolerance, winning-panel selection, imputation or substitution for a rejected required observation. Unused chart values are omitted from the returned feature map; the raw receipt-stamped snapshot remains archived unchanged outside Git.

This is input-availability repair, not model fitting or an accuracy improvement claim. The feature order, seven feature inputs, model weights, spike threshold and active trial policy/dates remain unchanged. The fix is not deployed into the running trial.

## Verification

- **47 focused Python tests passed**, including required-value conflicts, malformed required values in either panel order, irrelevant-history exclusion, HTTP failure behavior and schema-3/demand compatibility.
- A real public snapshot that the previous strict adapter rejected is accepted by the repaired adapter. All seven values exactly match those selected by the earlier running recipe. The saved model SHA-256 remains `e3d96c2dfb4a49a7698e3f9898bac0a896d90bedec8bd0543e637c116c2fe51d`.
- The latest application compiled/packaged and started against a fresh restore of the private local database after copy-only V23/V24 reconciliation. The rehearsal used the actual replay SQL at PR40 head `0c924594360d111e36f502707558d36da1adbefb`; that migration is unchanged from its earlier checked revision.
- Before app smoke activity, all original rows across 31 public tables and the trial metadata survived reconciliation. Validation succeeded, two subsequent migration calls executed zero scripts, and a separate original-backup restore validated against the older migration set.
- Real HTTP smoke checks exercised 24-point AI serving, identical predictions after an unused historical observation changed, actionable B3 fallback on a required conflicting value, recovery to AI, automatic household-advice refresh, unchanged trial pins and unchanged already-scored outcomes.
- Anonymous/resident access to the admin report was denied; resident access to another household was denied. The resident recommendations and public forecast pages remained accessible.

Negative-case input mutations and recovery metadata existed only in the isolated smoke snapshot. Test-only authenticated controls were loaded outside production source. Private backups, source captures, household data, credentials and generated datasets are not committed. Temporary app/Python processes and databases were removed; the original service process IDs, database history, trial metadata and model remained unchanged.

Compact evidence: [readiness receipt](evidence/2026-10-31-solar-readiness/verification.json). Full exact-head CI results are reported on PR48; earlier suite results are not attributed to a new commit. This runtime check used the PR48 application with the actual PR40 migration SQL, not a merged deployment or a test of PR40's separate replay-feature code.

## Rollout boundary

Keep PR40 separate and coordinate replay V23 before trial V24. After PR40 merges, update PR48 from main, resolve conflicts and rerun CI. The private existing instance needs the rehearsed, backed-up reconciliation and application cutover checks; ordinary repair or replaying its old trial DDL is not an upgrade procedure. Preserve its trial ID, model, dates and policy. No merge, live deployment, retraining or timer activation is performed by this fix.

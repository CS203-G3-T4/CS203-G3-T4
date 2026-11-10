# Scheduled review follow-up — 31 October 2026

Checked the actual heads, submitted reviews, discussion timelines and inline review threads of PR40 (demo live replay) and PR48 (forecasting delivery) at the owner's scheduled follow-up.

## Review state

- Jonathan (`Sterlid`) submitted a **COMMENTED** review on PR40 at `f751616`, on 29 October. It is not an approval, change request or review of the later `0c924594` head. No new review or inline thread was present at this check.
- PR48 at `cef4167` has no submitted reviews, discussion comments or inline threads. Jonathan and Michael remain requested reviewers. Agent verification is not independent human approval.

## Earlier demo review findings

The later PR40 commit `0c924594` already addresses the concrete comments: its description contains tracking/validation/rollback details; Java and CI use Java21; automatic household recomputation pauses during replay; demo forecast runs have a separate persisted flag and are excluded from ordinary accuracy/ranking; failed refresh uses a database transaction; a failed restart restores the previous clock state; the admin endpoint documentation and overview link are restored; and a forward migration removes the redundant index.

The shared business clock is still application-wide. Its effects on edits made during a demo are explicitly documented in the README. This is clarification of the chosen scope, not a claim of isolated clocks per household or feature-lead approval. The latest head still requires teammate review.

Fresh full Java/PostgreSQL verification on the unchanged PR40 head passed **381 tests**, zero failures/errors/skips, using a disposable database. The existing exact-head GitHub CI is green. The old description's local run with 11 skipped persistence tests is historical, not the current verification result.

## Remaining migration collision repaired

PR40's review-fix commit added **three** migrations: replay storage V23, the demo-forecast flag V24 and redundant-index removal V25. PR48 also named its trial migration V24. The earlier coexistence fixture included only replay V23, so it did not establish compatibility with the complete current PR40 migration set.

PR48 now names the unchanged trial SQL **V26**. The integration fixture contains the exact V23, V24 and V25 SQL from PR40 head `0c924594`. The updated PostgreSQL test upgrades V22 through the complete replay set, preserves an existing budget and demo forecast, applies only the trial V26 migration, validates history, checks the demo column/index effects, and confirms two subsequent migration calls execute nothing. The normal main-to-forecast upgrade test now expects V26.

The repair adds no new trial DDL, model, threshold, feature or dependency. The original trial SQL bytes are unchanged. No source file on the teammate's replay branch was modified.

## Restored-copy rehearsal

A fresh private backup was restored into two newly created disposable databases. On the upgrade copy, the existing old trial migration's identity was reconciled to V26 only after its checksum matched the unchanged SQL. Actual replay V23–V25 then ran once, with their real execution order recorded. This is a controlled copy-only rehearsal, not ordinary Flyway repair or instructions to edit a running database's history.

All existing rows across 31 public tables were preserved; the only addition to existing forecast rows was the expected `is_demo=false` column. Trial metadata remained identical. Flyway validation and two no-op migrations passed. A separate original-backup restoration validated against the old migration catalog. Both temporary databases were removed. The original live migration history, trial metadata and frozen model were verified unchanged.

The previous V24 rehearsal remains a dated receipt of its narrower V23-only check. It does not certify the complete replay upgrade and is superseded for rollout planning by this V26/full-replay rehearsal.

## Delivery and limitations

Fresh full Java/PostgreSQL verification on the PR48 repair source passed **389 tests**, zero failures/errors/skips. Python, frontend and product UI source are unchanged by this migration-number repair; earlier results are not attributed as new local runs. Exact-head CI is tracked on PR48 after publication. Compact [verification](evidence/2026-10-31-scheduled-review/verification.json) contains the pinned migration checksums and rehearsal receipts; private backups and source data remain outside Git.

Merge order remains PR40 then PR48. Bring main into PR48 after PR40 lands, resolve the Java integration conflicts and rerun the combined-source checks. The SQL test and copy rehearsal do not claim that the two PRs' complete Java implementations have already been merged or deployed. No merge, deployment, trial reset, automatic retraining, reviewer approval or messages to teammates were performed by this follow-up.

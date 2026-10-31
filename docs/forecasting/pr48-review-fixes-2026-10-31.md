# PR 48: 31 October 14:58 review follow-up

Jonathan reviewed `ea61c01` with a COMMENTED submission, explicitly a static read
without checkout/execution. This follow-up records repairs and evidence, not
approval. PR 40's 15:20 comments are handled in its separate replay report.

## Blocking comments

1. **Integration/order:** incorporated the reviewed PR 40 source, resolved the
   seven Java/UI/test conflicts, preserved solar input/run identity and demo
   classification, and retained both sets of regressions. Tests now read actual
   replay V23–V25 SQL; copied fixtures were deleted. V26's bytes are unchanged.
   V27 adds only capture-exclusion diagnostics. PR 48 must remain Draft until PR 40
   lands. Update from the actual main and rerun CI if upstream changes; merge order
   remains **40 then 48**. No GitHub merge or deployment was performed.
2. **Demo contamination:** persisted demo runs are excluded before trial capture
   and repository reads. AFTER_COMMIT events carry the demo marker, so capture and
   resident refresh ignore them even if the clock subsequently stops. Capture,
   initialization, evaluation and savings rechecks pause under demo time. A real
   PostgreSQL test saves an otherwise-eligible LIVE AI run through the replay
   repository's `is_demo=true` path and checks the event, manual capture and saved
   issue/opportunity/recommendation records. Existing ForecastJob demo-marking
   tests remain. Demos need a separate application **and database** from the trial.
3. **Capture loss:** an input read's REQUIRED transaction cannot poison the parent
   capture: household reads run in isolated read-only transactions. Appliance
   calculations/writes have JDBC savepoints. Failures record bounded reason codes
   and continue, preserving matched price pairs and unaffected households/runs.
   Tests inject a missing household and a real PostgreSQL division-by-zero during
   an appliance write, and verify other opportunities, 24 price pairs and exclusion
   records survive. A whole-database outage is not masked as successful capture.

## Other comments

- **Alert ownership:** the original `calibratedAcceptedStartAlertsAreQuietInsideRangeDeduplicatedAndHouseholdProtected`
  already checked cross-household GET/POST 403 and owner acknowledgement. It now
  additionally checks a foreign alert ID under the attacker's own household path
  returns 404 and leaves acknowledgement unchanged before the owner acknowledges.
- **Solar disagreement:** retained exact agreement for the frozen seven-period
  recipe. A tolerance or preferred-panel/latest-wins rule changes accepted input
  selection and is not introduced mid-trial. Admin evidence now reports eligible
  AI and generic baseline-fallback counts/fractions among captured non-demo runs,
  plus household/appliance exclusions. This does not attribute all fallbacks to
  solar or infer uncaptured-run coverage. Documentation now matches required-period
  checks. Any changed solar-selection policy needs separately versioned validation.
- **Collector size/storage:** all consumers and complete collector output agree on
  800,000 UTF-8 bytes; each response remains capped at 240,000. Wrapper overhead
  and multibyte strings are tested. Receipt-stamped archives intentionally do not
  deduplicate later observations. The solar runbook documents growth bounds,
  persistent monitored storage and preserving trial-referenced snapshots; no
  automatic deletion/rotation or source timestamp alteration was introduced.
- **Weekly order:** week keys are accumulated numerically and emitted in numeric
  order; a report regression checks weeks 1, 2 and 10.
- **Scorer:** removed unconditional `recommendedK`; fixed-k counts remain
  descriptive. Owner acceptance of k=3 stays a separate documented decision.
- **Blindness:** explicitly disclose that the workbook displays the same 28
  same-slot history values as the rule and may anchor reviewers. Hidden algorithm
  outputs and self-attestation do not establish independent ground truth.
- **Collection terms — unresolved:** the NEMS footer links to EMC terms whose
  section 3 prohibits automated systematic collection. Permission has not been
  established; see [decision](emc-collection-permission.md). No fresh automated
  collection was used for verification, and no permission, paid subscription or
  communication with EMC was fabricated. Existing runtime/operator decisions are
  separate from this source patch.
- **Scope/branch:** [ticket-to-file routing](pr48-review-routing.md) lists changed
  production/config/script paths. The existing published head branch is retained
  to preserve this PR; the title and new repair commits carry Jira keys. Use the
  reviewed conventional squash title. No scope split or teammate PR closure.
- **Deployment date:** [deployment audit](../live-comparison-and-savings-alerts.md)
  explicitly records deployment as pending. This repair has not been deployed;
  the actual timestamp must be recorded at rollout, with pre/post-repair evidence
  kept distinct. No trial reset, model change or frozen outcome rewrite.

## Verification

- 427 Java tests against disposable PostgreSQL 17, zero failures/errors/skips;
  37 focused tests repeated after strengthening the ownership assertion.
- 174 Python and 82 JavaScript checks passed.
- Frozen historical archive rebuilt/verified: 37 files, unchanged archive hash.
- Real Chromium with current HTML/JS and isolated synthetic responses: 20 cases at
  1280/390 px, including replay/resident denial and live-report loading, empty,
  error, finished/disabled and populated states; no page errors or page overflow.
- Restored-copy rehearsal preserved all original data across 31 tables and trial
  metadata; actual replay SQL applied once, original V26 SQL was not reexecuted,
  V27 applied once, validation and two no-op reruns passed. Original-backup restore
  passed separately. Test/rehearsal DBs removed; real history/model/trial unchanged.

[Machine-readable receipt](evidence/2026-10-31-review-fixes/verification.json).
Screenshots use **synthetic UI fixtures**, not measured model output:
[desktop](evidence/2026-10-31-review-fixes/live-1280-demo.png),
[mobile](evidence/2026-10-31-review-fixes/live-390-demo.png),
[loading](evidence/2026-10-31-review-fixes/live-390-loading.png),
[empty](evidence/2026-10-31-review-fixes/live-390-empty.png),
[error](evidence/2026-10-31-review-fixes/live-390-error.png),
[finished](evidence/2026-10-31-review-fixes/live-390-stale.png).

The earlier repaired source and this integrated source have not been activated on
the real trial. Current-head CI and independent human review remain separate
requirements; no review of an older head is an approval of these new commits.

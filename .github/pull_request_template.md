## Tracking and acceptance criteria

- Jira link(s), or reason for NO-ISSUE:
- Acceptance criteria delivered:
- Remaining criteria / follow-up work:

Do not mark a whole story complete when only part is delivered.

## Change summary

What triggered this, how behaviour differs before and after, and any significant
implementation choices or affected features, APIs, or interfaces.

## Validation

| Check / exact command | Result (passed / failed / not run) | Evidence or limitation |
| --- | --- | --- |
| Replace with relevant checks | | |

Manual steps and expected results (add screenshots for UI changes, including
loading, empty, error, and stale states):

## Compatibility and recovery

Write "Not applicable" with a reason if nothing below is affected.

- API / database migration / configuration / dependency effects:
- Setup or rollout steps:
- Rollback plan, including data effects (reverting code does not undo a migration):

## Shared clock (CSDT4-21)

If no time-dependent code is affected, say why this section does not apply.

- [ ] All business time comes from the injected `Clock` in `common/TimeConfig`.
- [ ] No direct `Instant.now()`, `LocalDateTime.now()`, or system-time calls were added.
- [ ] Tests use a fixed clock where behaviour depends on time.
- [ ] Persist instants; use Asia/Singapore for local dates and presentation.

For CSDT4-21 completion, record each feature lead's acknowledgement in the
shared Clock adoption checklist in `docs/jira-alignment.md`. Implementation
alone does not establish team confirmation.

## Review ownership

- Requested human reviewer (also select in GitHub's Reviewers field):
- Affected feature lead(s), if any:
- Areas needing particular attention:

One independent teammate with write access must approve the latest push. The
requested reviewer is not the only person who can approve.

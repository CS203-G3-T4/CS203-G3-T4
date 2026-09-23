## Jira item

Link the PBI and identify which acceptance criteria this change implements.
Do not mark a whole story complete when only its backend is delivered.

## Change and validation

Describe the resulting behavior and the checks performed.

## Shared clock (CSDT4-21)

- [ ] All business time comes from the injected `Clock` in `common/TimeConfig`.
- [ ] No direct `Instant.now()`, `LocalDateTime.now()`, or system-time calls were added.
- [ ] Tests use a fixed clock where behavior depends on time.
- [ ] Persist instants; use Asia/Singapore for local dates and presentation.

# Captured forecast fallback rates — 2 November 2026

Read-only database audit at **2 November 18:44:06 SGT**, using retained original
trial records. This supplies the measured-availability alternative in Jonathan's
solar overlap review comment. No float tolerance, panel precedence, input recipe,
model, trial, or running service changes were made.

## Recorded results

| Trial / captured issue range (SGT) | Captured runs | AI eligible | Baseline fallback | Other exclusion |
|---|---:|---:|---:|---:|
| Solar, 30 Oct 11:49:10–31 Oct 16:35:08 | 173 | 171 (98.84%) | 1 (0.58%) | 1 stale/future run |
| Price-only, 31 Oct 16:49:41–2 Nov 18:37:06 | 303 | 300 (99.01%) | 0 (0.00%) | 3 stale/future runs |

Solar trial ID is `solar-live-20261030-v1`; price-only is
`price-only-live-20261031-v1`. The one recorded solar fallback was at
**30 October 12:03:57 SGT**, selected B3, saved reason
`PYTHON_UNAVAILABLE_OR_INVALID`. Ineligible AI runs are not silently counted as
eligible, and price-only results are not attributed to the solar model.

These are **fractions of captured forecast issues**, not uptime or percentages of
expected polling opportunities. Repeated issues are not independent half-hours;
unrecorded failures, polling gaps and pre-trial startup runs are outside this
denominator. The two models ran in different periods with different code/input
conditions, so the table does not establish a causal availability improvement.
Solar captures ceased on switching serving to the separate price-only trial;
the original solar trial end date was not rewritten to make it look complete.

## Exact-overlap attribution remains unavailable

The saved Java fallback reason combines Python unavailability and invalid
responses. It does not preserve the particular solar adapter exception. The
historical Python journal query for the solar capture interval returned **no
retained lines**, not evidence of zero errors. Therefore the one fallback cannot
be conclusively attributed to a required-period overlap disagreement, and an
overlap-specific fallback rate is **unavailable**. Missing logs are not negatives.
No new remote source was polled and no old receipt was backdated for this report.

The [required-period repair](solar-input-readiness-2026-10-31.md) rejects
conflicting/malformed values among the seven consumed periods; disagreements in
unused history cannot veto inference. Keep that fail-closed behavior. The active
price/calendar/holiday-only model does not read solar inputs, so exact solar
overlap comparison cannot cause a new inference failure in its current recipe.
This is not evidence that a future solar/demand candidate is equally available.

## Reproduction without household data

Run against the retained private database with an authorized read-only account.
The database will have accumulated later issues: use the stated audit cutoff to
reproduce this snapshot. `captured_at` is also bounded; other SQL clients may
display timestamps in UTC rather than SGT. No household/appliance IDs are needed.

```sql
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT i.trial_id, coalesce(i.reason, 'ELIGIBLE') AS reason,
       f.selected_model, f.fallback_reason, count(*) AS runs,
       min(f.as_of) AS first_issue, max(f.as_of) AS last_issue
FROM live_forecast_issue i JOIN forecast_run f ON f.id=i.run_id
WHERE f.is_demo=FALSE
  AND i.trial_id IN ('solar-live-20261030-v1','price-only-live-20261031-v1')
  AND i.captured_at <= timestamptz '2026-11-02T10:44:06.055847Z'
  AND f.generated_at <= timestamptz '2026-11-02T10:44:06.055847Z'
GROUP BY 1,2,3,4 ORDER BY 1,2,3,4;
COMMIT;
```

Group eligible/null-reason rows separately from
`AI_UNAVAILABLE_BASELINE_FALLBACK` and `STALE_OR_FUTURE_RUN`; divide fallback
count by **all captured rows** for that trial. The original single-statement
aggregate audit and its result stay outside Git. Only this compact aggregate
report and read-only query are committed. Current-source regression coverage and
rehearsal evidence remain in the linked readiness record; this audit did not
rerun model accuracy tests or alter the frozen experiment.

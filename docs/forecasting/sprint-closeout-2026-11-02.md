# F3 sprint closeout — 2 November 2026

The application delivery and future research acceptance have different completion
conditions. This record does not turn an incomplete story green to meet the
sprint deadline. Source: live Jira descriptions/statuses and PR48 at preparation;
the table is a dated snapshot, not a replacement for Jira.

| Jira item | Status at closeout | Delivered / remaining |
|---|---|---|
| CSDT4-17 | Done | Walk-forward harness |
| CSDT4-18 | Done | B1/B2/B3 and accuracy scoreboard |
| CSDT4-34 | Done | Model-runtime decision |
| CSDT4-36 | Done | Forecast jobs/endpoints and fallback |
| CSDT4-51 | Done | Matched historical savings/error/spike report, including losses and limits; not consistent weekly superiority |
| CSDT4-52 | Done | Admin forecast-versus-actual view and defined error metrics |
| CSDT4-33 | In Progress | Statistical rule, dashboard, owner labels and reproducible scoring delivered; team sensitivity acceptance and independently meaningful validation still outstanding |
| CSDT4-35 | In Progress; carry forward | Price-only AI and demand feature/candidate infrastructure delivered; original real demand-aware fit and unseen comparison still required |
| CSDT4-59 | In Progress; optional carryover | Weekly tooling prepared/tested; scheduling/adoption inactive by owner instruction |

On 2 November Bryan explicitly chose **keep the demand requirement and carry
CSDT4-35 forward**. Do not replace it with price-only acceptance. Implementation
subtasks 93–95 and 100–103 keep their existing Done scope. F3 parent CSDT4-5
remains In Progress. No Jira transition or sprint-membership change is made by
this record. The team should carry unfinished work rather than record fictitious
acceptance; optional 59 is not a reason to activate retraining during a frozen trial.

## Delivery evidence ready today

PR40 is merged into the main integrated by PR48. Migration order is replay
V23–V25, trial V26, capture diagnostics V27. The integrated app/recommendation,
demo isolation, ownership and fallback checks are recorded in the
[final F3 report](../final-report-f3.md). The exact deployed Java source remains
`370928c`, activated 1 November 06:23:25 SGT. Later offline/docs changes do not
silently become a new runtime deployment.

The preceding source head `209fe61` has all five GitHub checks passing, full
Python 205 tests (including 24 portable-grader cases), 429 earlier Java/PostgreSQL
checks, 82 earlier JS checks and 28 earlier integrated browser cases. Counts are
dated by source in the final report; no new runtime suite is claimed for this
documentation-only closeout. Latest-head CI and review must be checked on PR48.

The [fallback report](forecast-availability-2026-11-02.md) reports solar 1/173
captured issues and current price-only 0/303, with exclusions and missing
exception-attribution evidence explicit. [Archive retention](emc-archive-retention.md)
and [portable spike scoring](spike-policy-reproduction.md) are documented.
No raw household/source data, model binaries, database backups, credentials,
operator scripts or generated datasets belong in this closeout commit.

## Team and time-dependent follow-up

* Review the latest PR48 push. Jonathan's `ce38ca6` approval is now dismissed;
  it was explicitly a static review and did not accept tasks 33/35. Michael and
  the relevant DB/API/feature leads still need their respective reviews.
* Agree task 33 sensitivity using the [fixed policy and limitations](csdt4-33-policy-2026-11-01.md).
  The convenience owner-label subset has precision 42.9% and recall 41.4%; it is
  not blind proof of an 80% warning-precision target. No teammate decision is
  inferred from Bryan's k=3 preference or passing tests.
* Keep the already reserved 1–8 November issue cohort and one score on
  **8 November 20:00 SGT**. Do not truncate, peek at, or re-label it as completed
  today. The 20-day calibration warm-up can suppress all warnings; that does
  not prove reliability. The longer price-only trial remains fixed through
  **28 November 16:48:09 SGT** and is supplementary to the completed task51 report.
* Carry task35's [registered demand experiment](demand-candidate-protocol-2026-10-30.json)
  forward: collection through 13 November 15:00 SGT, one permitted fit before
  14 November 15:00, untouched test ending 28 November 15:00, subject to the
  protocol's 480-complete-origin minimum per split and actual source permission.
  Do not fit early, change the frozen trial model or launch another search.
  **Readiness blocker:** the protocol selects original solar-model issues,
  not current price-only issues. Read-only inventory finds only
  52 distinct potentially eligible solar half-hours since
  its training start, last issue 31 October 16:35 SGT; this is an upper bound
  before full input/outcome validation, far below 480. More current price-only
  forecasts do not fill that cohort. Simply waiting for the dates will not make
  this schedule feasible under current serving. A reviewed source/collection and
  experiment-design decision is needed in the next sprint; preserve this frozen
  protocol rather than silently rewriting it or restarting solar serving.
* Keep the actual written EMC agreement in private team records. The owner has
  attested permission; this task did not inspect its text, duration or storage terms.
* Task59 activation, production promotion and merging PR48 remain separate
  decisions; this closeout neither schedules training nor merges/deploys code.

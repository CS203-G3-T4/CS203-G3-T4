# Price-only recommendation safety — 1 November 2026

The owner requested a fixed comparison of a S$0.10 minimum estimated saving and
an AI/B3 disagreement filter, followed by a current price-only report and PR48
integration checks. This study changes no live policy, model, trial or timer.

## Fixed policies

Six arms were declared before this replay: AI and B3 with S$0.05 or S$0.10
minimum saving, plus each AI floor with the agreement check. No extra thresholds
were searched after viewing outcomes. The S$0.10 floor is an owner product
preference, not a statistically optimised value.

The agreement check uses the same AI-proposed start: B3 must predict strictly
positive savings there compared with the usual start. If B3 predicts no saving
or a loss, the AI proposal is withheld at that refresh. Different preferred
start times alone do not constitute disagreement. There is no arbitrary price
distance threshold, and actual prices never enter this check or ranking.

The minimum is passed into the unchanged Java calculator before it chooses a
SHIFT/AVOID result. It is not a post-filter of the old result: a higher floor can
change an AVOID alternative or cause ordinary SHIFT comparison. After filtering,
the original top-three-per-household/refresh ranking and deterministic ties are
preserved. The first simulated acceptance locks each plan. A rejected proposal
may qualify on a later refresh, which can introduce new harmful cases.

## Results on the same 600 scenarios

| Policy | Moves | Weighted net savings SGD | Costlier moves / moves | Weighted harmful loss SGD | False/misleading advice cases |
|---|---:|---:|---:|---:|---:|
| AI, S$0.05 (current) | 446 | 34.0884 | 104 (23.3%) | 9.8119 | 141 |
| AI, S$0.10 | 305 | 29.4200 | 69 (22.6%) | 10.0004 | 92 |
| AI, S$0.05 + agreement | 427 | 34.0390 | 97 (22.7%) | 9.3460 | 136 |
| AI, S$0.10 + agreement | 290 | 29.5123 | 62 (21.4%) | 9.2642 | 85 |
| B3, S$0.05 | 431 | 31.0476 | 95 (22.0%) | 12.3648 | 146 |
| B3, S$0.10 | 292 | 27.0317 | 57 (19.5%) | 10.6000 | 94 |

Money uses the original runs-per-week/7 weights; counts are unweighted scenarios.
False advice means a shift with no actual positive saving, or an AVOID warning
without a canonical spike during the usual run. These are not delivered alerts,
independent market episodes or the raw forecast half-hour false-positive metric.
All arms use the same actuals, household seeds, feasible windows, power, duration,
price forecasts and refresh opportunities. The 600 scenarios are not 600 real
customers. B3 is also tested at S$0.10 for a fair same-floor comparison.

## Interpretation and stopping decision

- The S$0.10 floor reduces AI costlier moves from 104 to 69, but net savings fall
  S$4.6684 and harmful losses rise slightly from S$9.8119 to S$10.0004. It avoids
  44 originally harmful cases but introduces nine others. Fewer errors alone
  would give a misleading account of user impact.
- Agreement at S$0.05 retains S$34.0390 of S$34.0884 savings (about 99.86%),
  reduces harmful moves from 104 to 97 and harmful loss to S$9.3460. It avoids
  nine original harmful cases, introduces two, and withholds 11 originally
  profitable cases. This is a modest development improvement in harm at a small
  net savings cost, not evidence of a safe or reliably superior policy.
- Combining agreement and S$0.10 yields 62 harmful moves and S$29.5123 savings.
  At the same floor B3 has 57 harmful moves and S$27.0317 savings. The combined AI
  rule wins three weeks and loses five to B3; the current AI and agreement-only
  AI each win four and lose four against their S$0.05 B3 control.
- No policy establishes consistent weekly superiority. Do not automatically
  adopt a policy based on repeatedly examined development outcomes. Keep the
  frozen live comparison unchanged. This fixed assessment stops without another
  model/threshold search. Agreement-only can be considered for a separately
  frozen later evaluation if the owner chooses; it is not activated here.

## Data and verification limits

The cohort covers eight previously examined weeks, 1 June–26 July 2026: 2,496
forecast origins, 59,904 paired predictions and 12,064 refresh opportunities.
Each PRICE_183D weekly fit precedes its test week; the final July-fitted serving
artifact is never used to predict its own June-July training outcomes. This
tests a rolling recipe, not a single unchanged live artifact. Historical final
prices assume period-end availability; original as-issued receipt vintages and
actual household adherence are not reconstructed. It is development evidence,
not new untouched validation or measured bills.

The replay reproduces all 24,128 existing AI/B3 S$0.05 refresh decisions. Exact
rational time-overlap arithmetic checks all 3,600 final decisions, including
partial periods and negative prices. Input/forecast flags, feasible starts and
source hashes are checked. A second run through the parameterised CLI exactly
reproduces all six summaries; it verifies reproducibility, not an extra search.

Only compact derived scores are committed. Raw price files, scenario rows,
full input/output calls and model bundles remain private outside Git. Reviewers
need access to the existing study archives to reproduce the numerical results;
the repository alone does not contain these restricted/generated datasets.

## Reproduction

Use the pinned dependencies in `ml/requirements.lock` and a JDK supporting
`--release 21`, with a Java 21+ runtime. The CLI accepts paths and JVM executables;
it has no user-specific directories or credential dependency.

```sh
python scripts/compare-price-only-safety.py \
  --scenario-root "$SCENARIO_ARCHIVE" \
  --price-study "$PRICE_STUDY_ARCHIVE" \
  --reference-replay "$PRICE_ONLY_REFERENCE_REPLAY" \
  --output "$NEW_EMPTY_OUTPUT" \
  --javac javac --java java
```

The scenario archive needs inputs.npz, cohort.json, plan.json and
raw/opportunities.jsonl.gz. The price study needs its protocol/results/checksum
files and week-1 through week-8 predictions. The reference replay needs its
protocol, summary, verification, F4Replay.java and AI/B3 input/output TSVs. The CLI
refuses nonempty output and mismatching reference inputs/calculator identities.

[Aggregate comparison and verification](evidence/2026-11-01-price-only/comparison.json)
· [Updated final report](../final-report-f3.md).

## Integration validation — 1 November 2026

PR40 is merged. Actual main `ee11c05c92d1098d2fcc45275b9b767827911ca0` was merged into the
isolated PR48 worktree without conflicts or file changes. A fresh dedicated
PostgreSQL test database was created and removed; the live database was not
used for test fixtures. The complete combined-source checks passed:

- Java/PostgreSQL: 427 tests, zero failures/errors/skips.
- Python: 181 passed.
- JavaScript: 82 passed.
- Frozen archive: 37 files rebuilt and verified with unchanged hash.
- Generic safety CLI: all six summaries exactly reproduced; existing-output
  refusal, relative documentation links and source identities checked.
- Live read-only guard: active price-only model, 24 actionable points, original
  model/trial pins, S$0.05 policy, old solar-trial metadata and migration history
  preserved. The runtime's source worktree is clean at the prior head.

The V23-V25 SQL matches merged main; V26/V27 ordering is preserved. No fresh
browser run is claimed because this update changes no UI. No deployment, model
replacement, trial reset or retraining activation occurred.

[Validation evidence](evidence/2026-11-01-price-only/validation.json). Latest-head
GitHub CI and independent review remain separate PR checks.

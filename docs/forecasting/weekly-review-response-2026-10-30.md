# Weekly audit / delivery review follow-through

Review reference: PR #41 at 71a1e2c. The review was a static comment, not an independent approval. No model was fitted or promoted while addressing it; no timer or current runtime changed.

## Transport validation

Preflight and `operations.post_admin` now call the same `audit_url` validator. HTTPS is accepted; HTTP requires `localhost` or a literal loopback IPv4/IPv6 address. Credentials, queries, fragments and other schemes are rejected. Preflight rejects invalid transport before its health request. The shell script imports the validator from its own checkout, avoiding an unrelated installed package. Existing login, CSRF rotation, endpoint allowlist and no-redirect behavior are unchanged.

The focused Python checks (`test_review_preflight.py`, `test_operations_http.py`, `test_warning_budget.py`) pass: **41 tests**. They cover URL policy, actual shell preflight rejection, existing real HTTP authentication, and policy/CLI behavior. These are synthetic correctness checks, not model performance evidence.

## Worst-week cap: intended interpretation

The implemented gate is a retrospective research review at one explicit `decisionAt`. The maximum exposure-normalized control rate across **all completed weeks with labels available before that decision** sets the common cap. It is intentionally not a cap recalculated using only weeks before each candidate week. A later week inside the completed cohort may set the cap for an earlier week. The output now states the `capWindow` and this meaning. Post-decision weeks/labels remain rejected. There is no model selection, promotion, or online decision claim. The sensitivity-policy text now agrees with the implementation; no numerical policy was tuned or changed.

## Reproducing the saved savings comparison

`compare-forecast-candidates.py` now parses JSON before filtering the evaluation boolean, so JSON whitespace cannot drop rows. It no longer asserts an incidental 12,064 input-row count. The matched 600-case identity and existing cost checks remain required for this particular fixed comparison.

Java tool locations are configurable using `--javac` and `--java`, defaulting to PATH. Use a compiler supporting `--release 21` and a Java 21+ runtime. This removes the author's JDK25/JRE21 paths from the source.

The reported S$37.2051 / S$27.1044 / S$31.0476 values are the previously verified development comparison. This review fix did not recompute them. Reproduction requires the original trusted, immutable research input bundle under `--research-root`: the two `.local` study folders, NPZ/cohort/plan/source summary, compressed opportunities, eight saved weekly forecasts and F4 adapter. Those generated inputs are not distributed in this source PR. Configurable paths do not make private artifacts publicly available. The committed scoreboard and prior verification remain summaries of that earlier run, not a claim that a clean clone can recreate it unaided.

## PR metadata and remaining collaboration decisions

The auth/weekly fixes, warning-budget assessor and final F3 evidence all need to be named in the PR description; the title should include the relevant Jira key. Latest-head CI must be reported by exact revision instead of leaving an old "needs checking" note.

No `CONTRIBUTING.md` was found in the checked main or PR trees. The current `.github/pull_request_template.md` does require tracking, screenshots for UI changes, compatibility/rollout notes, Clock disclosures and independent latest-head review. The reviewer's branch-ticket convention can still be followed for new branches; it must not be treated as authorization to rename a published head and close its open review. The prepared fix branch has a ticket key. Existing review branches have not been renamed or closed. No independent teammate approval or feature-lead acknowledgement is asserted.

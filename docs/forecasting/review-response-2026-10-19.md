> Historical verification at `2c7fbb4`. The replacement PR incorporates later main and uses forecast V15; see [current merged verification](main-sync-v15-2026-10-19.md). Original results below are preserved.

# PR #11 review response — 19 October 2026

Addresses Jonathan Gladwin's review of `76c4866`. Current main `3c6e3b7` was
fetched and merged without dropping teammate work. No `AGENTS.md` or
`CONTRIBUTING` could be found in the checkout, its ancestor directories or
current main. The actual PR template and existing project conventions were used;
the review's policy citations could not be verified. No acknowledgement from an
auth/F1 owner or a human approval is implied by this response.

## Concern checklist

| Concern | Status | Change, verification and remaining merge action |
| --- | --- | --- |
| Migration collision | Fixed and verified; legacy owner decision conditional | Main's recommendation V12 is byte-identical. Forecast operations move, byte-identically, to V13. `ForecastMigrationUpgradeTest` verifies main V12→V13 preserving budget, refresh and model data, plus safe refusal of legacy forecast V12. Fresh V13 is exercised by persistence tests. [Lineage inspection and forward/rollback plan](migration-v13.md); any durable legacy-V12 DB needs its owner's cutover decision. |
| Generated artifacts and scope | Fixed and verified | 37 generated files/full demo preserved in the SHA-256 archive and original Git history. Build/verify/restore and corruption/overwrite tests pass. [Artifact access and scope decision](artifacts.md); CI publishes the reproducible archive. Dependent Java/Python contracts/UI/tests remain together. |
| Admin ownership | Fixed and verified locally; awaiting owner decision | Explicit local-demo flag, six exact forecasting routes, no default credentials, shared principal/ADMIN contract, no resident elevation. Seven filter tests and real HTTP/browser denial/access pass. Shared login is on an unmerged branch; [owner proposal](auth-owner-decision.md) identifies page policy, machine-client/CSRF and merged-session tests still needed before production use. |
| Java maintainability | Fixed and verified | Both operations INSERTs name columns; persistence tests add an extra defaulted column and preserve deduplication/immutability. Controller/job each have one constructor with final dependencies; job tests supply an operations mock and retain coalescing/outage coverage. |
| Weekly business time | Fixed and verified | `scheduled.main(now=...)` captures one aware instant for default validation boundaries/version identity; real audit time stays separate. Four fixed-time cases cover Singapore midnight, explicit overrides and naive-time rejection, including unique repeated-attempt identities. Existing historical replay, promotion/reload/rollback tests pass. |
| F1 recovery event | Verified and preserved; owner awareness pending | `MarketIngestionService` is unchanged from reviewed head. Startup/import duplicate recovery and recovery after failure notify after saved status; successive successful duplicates do not. Existing regression and new main's recommendation-worker tests pass. [Event semantics](runbook.md#review-correction-local-auth-artifacts-and-recovery) are ready for F1-owner review; no acknowledgement is claimed. |
| Scientific/product claims | Verified and preserved | No model/threshold retuning or promotion. Frozen candidate/future-plan hashes match. Reports retain 8/13 versus 4/13 warnings with 54 versus 13 false targets and AI household savings below B3. No genuine-spike, measured-bill, sensitivity-approval or permanent-timer claim. |
| Target-runtime validation and PR publication | Verified locally; latest-head human review required | Java21/Python3.12/PostgreSQL17 tests, builds, resources, actual resident/admin/demo browsers and timer proof pass. See exact results below. Push/update the existing PR and re-request Sterlid, preserving other reviewers; do not merge or change Jira. |

## Fresh checks

These are new checks for this correction, not Jonathan's earlier Java26 run or
the October18 evidence. [Compact machine evidence](evidence/2026-10-19-review/verification.json)
includes browser results, migration/resource hashes and skipped checks.

| Check | Result |
| --- | --- |
| Full Java suite, JDK21.0.12.1, isolated native PostgreSQL17.11, persistence/migration enabled | **173 passed; 0 failed/errors/skipped** |
| Full Python3.12.3 suite | **57 passed; 0 failed/skipped**, 388.51s, one upstream Starlette/AnyIO deprecation warning |
| Frontend `node --test src/test/js/*.test.js` | **12 passed; 0 failed/skipped** |
| Final PostgreSQL/application proof after schema-extension assertion | **173 Java passed again**, plus **8 focused Python acceptance tests passed**; full Python/frontend were not rerun in that second invocation |
| Spring jar and Python wheel builds | Passed |
| Jar contents, all main migration hashes, V13 versus reviewed forecast-V12 SQL, public report source hash | Passed |
| Archive deterministic rebuild, verify, restore, per-file checksums, changed-content/overwrite rejection | Passed, 37 files |
| Real Spring/FastAPI HTTP, actual Python outage and ranked Java fallback | Passed inside isolated persistence test; fixture is explicitly synthetic |
| Actual resident/admin, dashboard/recommendations/budget, desktop/mobile | Passed; real saved resident B2 has 24 future points and two today's actual intervals; no browser errors/overflow |
| Populated admin chart fixture | Passed with three chart pairs; fixture is labelled, not measured accuracy evidence |
| Bundled compact historical-demo page | Passed; 24 displayed points, one chart origin, 24 aggregate scored origins, 576 pairs; 224.407932695209 SGD/MWh unchanged |
| Weekly failure/rejection auditing, promotion/reload/rollback/in-flight identity | Passed; real bounded short-history attempts exit2 and are persisted, synthetic lifecycle regressions preserve champion/rollback |
| Ephemeral native user timer/resource limits | Fired successfully, then stopped/reset; no permanent schedule installed |

The first focused compile attempt used an installed Java21 runtime without its
compiler and failed with `release version 21 not supported`. Selecting the
dedicated Java21 **JDK** resolved it; the final target-runtime checks above passed.
The archive/collector timestamps were not changed. A read-only snapshot included
recent SQLite WAL data, so the real resident flow had usable observations; stale
inputs remain unavailable rather than being made fresh by the proof harness.

Raw logs/screenshots/private disposable runtime files remain under
`.local/review-fixes-2026-10-19/stack-v1` and `stack-v2`; private files are excluded
from Git and the evidence archive. All temporary test processes/databases/timers
were stopped; the independent collector and persistent databases were untouched.

Not run: Docker Compose container runtime; merged shared-auth session login;
legacy persistent-DB cutover; future October19–November1 confirmation (requires
a snapshot on/after November2 00:00 SGT). Those checks are not described as passed.

## Reproduce

Install the pinned ML requirements as described in the runbook and a separate
test-only Playwright1.58.2 with its Chromium. Use a full Java21 JDK and native
PostgreSQL17. Set the following paths for the machine running the proof:

```bash
export JAVA_HOME=/path/to/java21-jdk
export WATTLY_PYTHON=/path/to/python3.12-venv/bin/python
export WATTLY_PG_BIN=/path/to/postgresql/17/bin
export NODE_PATH=/path/to/test-tools/node_modules
export PLAYWRIGHT_BROWSERS_PATH=/path/to/test-tools/browsers
export WATTLY_STACK_PROOF="$PWD/.local/new-review-proof"
"$WATTLY_PYTHON" scripts/verify-forecast-stack.py
# Full Java+Python+frontend, builds, isolated app/DB/browser/timer proof.
# Use a fresh output directory for another run.
```

The actual runs used the dedicated tools under
`/home/bryan/CS203_T4/wattly-f3-demo/tools` and PostgreSQL17 under
`.local/pr-safety-tools/postgresql17/usr/lib/postgresql/17/bin`. The script creates
its own cluster and overrides database URLs. Never point destructive tests at a
live database. `--application-only` repeats Java and focused Python before the
application proof; it does not repeat the full Python/frontend suites.

For the compact demo browser check while an isolated application is running:

```bash
WATTLY_DEMO_PROOF_DIR=target/compact-demo-browser \
  node scripts/forecast-demo-browser.cjs http://127.0.0.1:ISOLATED_APP_PORT
```

See [archive commands](artifacts.md#retrieval), [migration rollout/recovery](migration-v13.md)
and [timer installation/status/failure inspection/uninstall](runbook.md).

## Results and dependencies kept visible

The existing modelled replay reports **SGD168.99 AI savings versus SGD176.43 B3**,
an AI loss of **SGD7.45**, over 497 distinct appliance runs. Consumption is modelled,
savings simulated, fixed-rate households are not shifted, and losses remain in
totals. These are preserved prior results, not new measured household bills.

Recent spike development coverage reports 8/13 versus 4/13 advance-warned events,
but 54 versus 13 false-alarm half-hours and precision 28.9% versus 40.9%.
The 125-event historical strategy check ties at 31 advance warnings. These are
fixed-rule anomalies on identical within-panel pairs, not independent genuine
spike labels or proof of improved resident outcomes. Historical final-price
studies remain separate from operational publication-vintage evidence.

Before merge: obtain an independent human review of the latest push; ask the
auth owner to decide the temporary boundary and shared-client contract; confirm
whether any durable forecast-V12 database needs the documented cutover. F1-owner
awareness is pending, not manufactured. Independent spike labels, an agreed
false-alarm budget, origin-known historical demand and the future confirmation
remain data/acceptance dependencies; this corrective PR does not resolve them.

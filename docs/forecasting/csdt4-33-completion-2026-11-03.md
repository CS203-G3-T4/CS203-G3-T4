# CSDT4-33: current-price assessment completion — 3 November 2026

This follow-up closes the two implementation gaps found at `b8aca16`: missing
assessment/deviation on the main Dashboard, and confident labels on stale or
unverified inputs. It changes neither the statistical rule nor model predictions.
Jira was not edited. This is agent-run implementation verification, not an
independent human approval, deployment or new model-accuracy result.

## Behaviour delivered

- The Dashboard now displays Cheap now / Normal / Spike and deviation from the
  preceding same-half-hour reference, alongside its existing market price.
  The intermediate EXPENSIVE class remains “Normal (above typical)”.
- Stale prices, unverified intervals, absent prices and insufficient reference
  history produce explicit unavailable assessments. Recorded prices remain
  visible separately when present; unavailable assessments have no typical,
  deviation or spike threshold. Both pages use the same wording.
- The additive API field `periodStart` identifies the assessed half-hour. The
  Dashboard requires both period and numeric price to match its price card.
  Independently arriving price/assessment updates cannot borrow another
  snapshot's label. Older refresh responses cannot overwrite a newer result.
- Slow or failed assessment requests leave current prices and Refresh usable.
  Price API staleness also suppresses a confident assessment on the Dashboard.
  The shared renderer rejects old-server responses that combine a confident
  label with a stale/unverified flag.

The fixed rule is unchanged: preceding28 daily observations at the same Singapore
half-hour, minimum14 samples; median typical; spread `max(1 SGD/MWh,1.4826*MAD)`;
strictly `price > typical + 3*spread`. Reference values must have been available
by the injected assessment time. Equality to the threshold is not a spike.
Demo replay retains its replay-price period and requires verified reference data.

## Validation

| Check | Result | Scope |
|---|---|---|
| `./mvnw -B -ntp -Djvm=/usr/lib/jvm/java-21-openjdk-amd64/bin/java test` | 439 passed,0 failed,0 skipped | Java25 compilation/Java21 test JVM; disposable independent PostgreSQL17 supplied through `WATTLY_TEST_DB_URL`/`WATTLY_TEST_DB_USER`. |
| `node --test src/test/js/*.test.js` | 91 passed,0 failed,0 skipped | Node24; classification, failure states, exact-price/period identity, refresh races and existing frontend regressions. |
| Actual isolated Spring app + Chromium | 26 page/state/viewport cases passed | Authenticated resident; fresh spike/normal/cheap, insufficient, stale, unverified, empty, assessment outage/loading and mismatched-price responses; widths1280/390; no page JavaScript errors or horizontal overflow. |
| API responses from the isolated app | Passed | Valid classifications expose the period; stale/unverified/empty/insufficient states return `available=false`, `classification=UNAVAILABLE` and no confident reference. |
| `git diff --check` | Passed | Source/test/document changes only. |

The browser database contained synthetic prices (typical100, threshold103,
current80/100/120 SGD/MWh). Real app authentication, migrations, API, repositories
and static pages were exercised. Assessment outage/loading/mismatch cases used
browser response interception; they are not upstream outage or accuracy tests.
Weather and recommendations intentionally had no live inputs in this fixture.
The disposable Spring process and independent PostgreSQL were stopped afterward.
No live database, host app, collector, timer or frozen trial was modified.

Screenshots contain only the disposable fixture account/household and prices:

| State | Desktop Dashboard | Phone Dashboard |
|---|---|---|
| Fresh spike | [Desktop](task33-ui/spike-1280-dashboard.png) | [Phone](task33-ui/spike-390-dashboard.png) |
| Loading assessment | [Desktop](task33-ui/loading-1280-dashboard.png) | [Phone](task33-ui/loading-390-dashboard.png) |
| No price | [Desktop](task33-ui/empty-1280-dashboard.png) | [Phone](task33-ui/empty-390-dashboard.png) |
| Assessment outage | [Desktop](task33-ui/error-1280-dashboard.png) | [Phone](task33-ui/error-390-dashboard.png) |
| Stale price | [Desktop](task33-ui/stale-1280-dashboard.png) | [Phone](task33-ui/stale-390-dashboard.png) |
| Unverified interval | [Desktop](task33-ui/unverified-1280-dashboard.png) | [Phone](task33-ui/unverified-390-dashboard.png) |

Forecast-page examples: [fresh](task33-ui/spike-1280-forecast.png),
[stale](task33-ui/stale-1280-forecast.png),
[unverified phone](task33-ui/unverified-390-forecast.png).

## Acceptance limits and rollout

The agreed observed-price definition and its implementation/display criteria are
delivered. The existing historical owner-labelled subset remains descriptive:
TP12/FP16/FN17/TN172 on217 definite convenience labels, precision42.9%, recall41.4%.
These figures are not a blind test or proof that the personal-warning precision
target is met. This patch does not improve spike prediction or turn on S$0.10
personal warnings. The reserved8November validation, frozen trial, model,
data/policy hashes and live S$0.05 recommendation policy remain unchanged.
The original policy/evaluation evidence remains in
[the accepted definition and validation protocol](csdt4-33-policy-2026-11-01.md).

No database migration, configuration change or additional dependency is required.
The UI and API should ship together: an older API lacks `periodStart`, so the
new Dashboard intentionally withholds its classification while keeping the price.
Rollback is a source/build rollback; there is no schema/data rollback.
Business time continues to come from the injected Clock, with fixed-clock Java
tests. The model reference function and forecasting jobs are unchanged.
Deployment and independent latest-head teammate approval remain separate steps.

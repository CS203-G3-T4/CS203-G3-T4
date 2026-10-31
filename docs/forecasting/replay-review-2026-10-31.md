# PR 40: 31 October 15:20 review follow-up

Reviewed against Jonathan's comment on `0c924594`. This records implementation
and local verification, not human approval or deployment.

1. Presenter polling stops on the first 401/403, including queued callbacks.
2. Removed the duplicate Reset control. Stop ends the replay; the form starts a new one.
3. Start/advance/stop share a serialized transition. Its thread sees staged time;
   other threads see only the last published state until the transactional replay
   service returns after commit. A failure discards staged time. Zone views share
   state. Concurrent failed advance/stop and publication visibility are tested.
4. Advance rejects leaving the selected Singapore day with HTTP 400, without a refresh.
5. Internal replay setup/refresh failures return HTTP 500; bad requests remain 400.
6. Restored main's full overview/household-summary endpoint descriptions.
7. Forecast status, metadata, chart legend and presenter bar explicitly identify
   historical replay; a zero-error replay is not evidence of model accuracy.

Use a separate application **and database** for demo and live trial. This is
documented in README. Do not start a demo on the prospective comparison instance.
PR 48 must explicitly exclude persisted demo runs in addition to that isolation.

Validation: 384 Java tests, zero failures/errors/skips, including PostgreSQL
tests against a fresh disposable database, subsequently removed. Native JS tests
and replay-render/polling regressions passed. Browser screenshots are recorded in
the final review evidence after the combined integration check. No live instance,
trial policy, model artifact or real database was changed.

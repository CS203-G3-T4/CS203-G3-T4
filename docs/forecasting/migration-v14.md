# Forecast operations migration V14

The replacement PR integrates main `b89bf25`, which adds `V13__recommendation_spike_warnings.sql`. Main V1-V13 are preserved unchanged. Forecast operations now use V14 with exactly the SQL bytes previously reviewed as forecast V12/V13.

## Before startup

Read the actual database schema's `flyway_schema_history` without changing it. Distinguish canonical recommendation V12/V13 from earlier forecasting migrations with the same version numbers.

- A fresh database or canonical main through V13 follows normal migrations through V14.
- A durable database with `V12__forecast_operations.sql` or `V13__forecast_operations.sql` belongs to a legacy lineage. Do not apply canonical migrations blindly. Validation should fail while existing business data/history remains intact.
- Never run Flyway repair/clean, change checksums, or delete data to hide the collision. No persistent database is modified by this PR.

The database owner must approve a rehearsed copy-and-cutover plan for any legacy lineage. Preserve the original database and application for rollback, restore a backup to an isolated database, create a separate canonical V14 target, explicitly map and verify all business rows/keys/sequences, and switch the application connection only after acceptance. Do not copy old Flyway history into the canonical target. The detailed non-destructive planning considerations remain in the [historical lineage document](migration-v13.md).

Regression tests cover fresh V14, canonical main V13 to V14 while preserving budget/recommendation/model rows, and refusal of both legacy forecast V12 and V13 without repairs. Earlier V13 verification reports remain historical evidence and are not relabelled as V14 tests.

Build cleanly so an obsolete forecast-V13 copied resource cannot remain in the jar. Retain canonical migration resources when rolling back compatible application code; do not restart the older conflicting V12/V13 forecasting artifact unchanged against a canonical V14 database.

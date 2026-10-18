> Historical checkpoint at `2c7fbb4`. Current main now owns V13; use [V14 compatibility](migration-v14.md) for the replacement PR.

# Forecast operations migration: V12 to V13

Current main `3c6e3b7` contains recommendation refresh V12. This branch preserves
that file byte-for-byte and moves its unmerged forecast SQL to **V13**, with
identical SQL bytes. V1–V11 remain unchanged. Existing `ForecastRepository`
readers/writers keep their contracts; the operations repository adds independent
baseline validation, origin-known demand and training-attempt metadata. V13 is
expand-only, with no data backfill, deletion or checksum changes.

**Unmerged does not mean unapplied.** Earlier disposable PostgreSQL tests applied
forecast V12. Their logs remain evidence, not a reason to rewrite their history.
The persistent demo cluster's saved September 30 log reports V9. Its
`postmaster.pid` is stale/offline (read-only connection to its configured 55440
port was refused during this review); no persistent history could be certified.
No persistent cluster was started, changed, repaired or deleted. Other teammates'
databases were not inspected. Determine each database's actual lineage before
starting the new application.

## Inspect before upgrading

Use the database owner's configured connection and the actual schema (examples
use `f3_presentation`). Run only this read-only query first:

```sql
BEGIN READ ONLY;
SELECT installed_rank, version, description, script, checksum, success
FROM f3_presentation.flyway_schema_history ORDER BY installed_rank;
ROLLBACK;
```

| Existing lineage | Forward path |
| --- | --- |
| Empty database | Normal Flyway migration through V13. |
| Original team snapshot V9, V11 household budget, or main V12 recommendation refresh | Normal migration to V13; preserve existing checksum history. |
| V12 forecast operations | **Do not start canonical migrations on that database.** Validation deliberately fails. Use the separately planned copy-and-cutover path below. |

Build with Java 21 using `./mvnw clean test`/`./mvnw clean package` so obsolete
copied V12 resources from an earlier build cannot remain in `target/classes`.
Tests exercise fresh V13, current-main V12→V13 (retaining budget, recommendation
refresh and model rows), and rejection of legacy forecast V12 without mutation.

## Legacy forecast-V12 databases

There is no safe rename of an applied version to V13: main's V12 is different,
and blindly replaying V13 also collides with existing operation tables/columns.
Never edit `flyway_schema_history`, stored checksums or applied SQL; do not run
`repair`, `clean`, or bypass validation. Keep the old application/database paired
until the database owner chooses the cutover and approves its table mapping.

The non-destructive forward plan is:

1. Quiesce writes in an agreed maintenance window; take a verified full custom
   `pg_dump` backup including schema/history and a second business-data export.
   Keep the old database intact for rollback.
2. Restore that backup to a separate isolated PostgreSQL 17 database to establish
   counts, primary keys, sequence values and all-table fingerprints. Do not use
   the live database for migration experiments.
3. Create a separate canonical target database through V13. Define an explicit
   column/key mapping for every old business table, including household/appliance
   seeds, forecasts, model/audit rows, demand vintages and ranking evidence.
   Reconcile seeded keys with explicit inserts/upserts in a transaction; do not
   import old Flyway history or blindly restore a data dump over seeded tables.
4. Verify every mapped table's contents, references and sequence positions,
   saved forecast immutability, and old/new API responses. Rehearse before the
   maintenance window; keep immutable artifacts and pointers with the matching DB.
5. Switch only the application connection after owner acceptance. Roll back by
   reconnecting the old application to the untouched original database. Avoid
   dual writes; reconcile any post-cutover writes before a later rollback.

The mapping/cutover has **not** been implemented or run against an unknown
persistent database. Required decision: database owner confirms whether any
legacy forecast-V12 database contains durable data and approves its migration
mapping/window. Normal fresh/main upgrades are independent of that decision.

For code rollback after a canonical V13 upgrade, retain the V12/V13 migration
resources and expanded schema while rolling back compatible application code.
Do not restart reviewed head `76c4866` unchanged: its conflicting V12 resource
belongs to the legacy lineage. No destructive schema contraction is required.

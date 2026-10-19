# Forecast operations migration V15

PR #21 now merges main `c8ccfda` (including PR #20). Main owns recommendation
refresh V12, spike warnings V13 and confidence/explanations V14. All main SQL
V1–V14 stays byte-identical. Forecast operations move from the unmerged V14 to
**V15**, with exactly the SQL bytes reviewed at `195ab04`.

Textual Git mergeability does not validate Flyway's version namespace. Two V14
files would prevent startup. Build cleanly (`./mvnw clean test` or clean before
the isolated proof) so a copied obsolete forecast-V14 resource cannot remain in
`target/classes` or the jar.

## Before startup

Read the actual schema's `flyway_schema_history` without changing it. Unmerged
does not imply unapplied: earlier research/test or persistent databases might
already contain forecast V12, V13 or V14.

- Empty databases or canonical main through recommendation V14 migrate normally
  through V15. The migration remains expand-only, with no backfill or deletion.
- A database containing `V12__forecast_operations.sql`,
  `V13__forecast_operations.sql` or `V14__forecast_operations.sql` belongs to a
  legacy lineage. Stop before canonical startup. Validation must fail while
  saved history and business data remain intact.
- Never use Flyway repair/clean, rewrite applied SQL/checksums or delete business
  data to hide the collision. No persistent database is changed by this fix.

For a durable legacy database, the owner must approve a rehearsed backup,
copy-and-cutover plan. Preserve the original database/application for rollback;
restore a backup to an isolated PostgreSQL17 database; create a separate
canonical V15 target; explicitly reconcile business rows, seeded keys and
sequences; verify fingerprints/references/API results before switching the
application connection. Do not copy legacy Flyway history into the new target.
See the detailed [non-destructive lineage planning](migration-v13.md).

`ForecastMigrationUpgradeTest` covers main V14→V15, retaining household budget,
refresh/model history and PR #20's confidence counts and 550-character
explanation. Legacy forecast V12/V13/V14 each refuse migration without repair or
loss of the saved model row. Persistence tests also exercise fresh V15 databases.

Keep canonical migration resources and expanded schema when rolling back
compatible application code. Older conflicting forecast-V12/V13/V14 artifacts
must not restart unchanged against the canonical V15 database. No destructive
schema contraction is part of this change.

The earlier 204-test report covered `195ab04` with main `b89bf25`, not this merged
tree. [Current merged verification](main-sync-v15-2026-10-19.md) records fresh
results separately; historical evidence is retained without relabelling it.

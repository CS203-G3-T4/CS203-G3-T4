# Historical forecast V14 checkpoint — superseded

Reviewed head `195ab04` used forecast V14 against main `b89bf25` (recommendation
V1–V13). [The original policy remains in Git history](https://github.com/CS203-G3-T4/CS203-G3-T4/blob/195ab04cc6ac99e6d81e9af292b7e610c512b9c9/docs/forecasting/migration-v14.md).

Main `c8ccfda` now owns **V14 recommendation confidence**. Forecast operations
use **V15**. Follow [current startup, upgrade and rollback policy](migration-v15.md)
and [fresh merged-tree verification](main-sync-v15-2026-10-19.md).

An applied forecast V14 is a legacy lineage, not an unapplied file that can be
renamed in database history. Stop canonical startup until its owner approves a
safe copy/cutover. Never repair checksums or delete data to conceal the collision.

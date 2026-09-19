-- Reconciles the deviation flagged in V1__baseline_schema.sql: TASK-01 left
-- exceptions.type/severity nullable because ingestion alone (with no
-- classifier yet) couldn't populate them. TASK-02 (rule-based classifier)
-- now computes both synchronously, in the same transaction as ingestion, so
-- every row committed to this table from here on has non-null type/severity
-- — restoring the NOT NULL constraint the TDD §4.1 DDL specifies.
--
-- Safe to run on a fresh/demo database with no pre-existing null rows; this
-- is not a backfill migration.

ALTER TABLE exceptions ALTER COLUMN type SET NOT NULL;
ALTER TABLE exceptions ALTER COLUMN severity SET NOT NULL;

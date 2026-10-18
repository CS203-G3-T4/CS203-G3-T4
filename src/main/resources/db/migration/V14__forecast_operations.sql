-- Expand only: older forecast readers/writers continue to work during rollout.
CREATE TABLE baseline_validation (
    id TEXT PRIMARY KEY,
    mode TEXT NOT NULL CHECK(mode IN ('LIVE','REPLAY')),
    usable_from TIMESTAMPTZ NOT NULL,
    validation_start TIMESTAMPTZ NOT NULL,
    validation_end TIMESTAMPTZ NOT NULL,
    dataset_digest TEXT NOT NULL,
    evidence JSONB NOT NULL,
    CHECK(validation_start < validation_end AND validation_end <= usable_from)
);
CREATE TABLE expected_demand_revision (
    target_period TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ NOT NULL,
    available_at TIMESTAMPTZ NOT NULL,
    demand_mw NUMERIC NOT NULL CHECK(demand_mw >= 0),
    source TEXT NOT NULL,
    PRIMARY KEY(target_period,published_at,available_at,source),
    CHECK(published_at <= available_at)
);
ALTER TABLE model_run ADD COLUMN attempt_id TEXT UNIQUE;
ALTER TABLE model_run ADD COLUMN model_version TEXT;
ALTER TABLE model_run ADD COLUMN training_date TIMESTAMPTZ;
ALTER TABLE model_run ADD COLUMN training_start TIMESTAMPTZ;
ALTER TABLE model_run ADD COLUMN training_end TIMESTAMPTZ;
ALTER TABLE model_run ADD COLUMN dataset_digest TEXT;
ALTER TABLE model_run ADD COLUMN scores JSONB;
ALTER TABLE model_run ADD COLUMN outcome TEXT CHECK(outcome IN ('RUNNING','SUCCESS','REJECTED','FAILED'));
ALTER TABLE model_run ADD COLUMN reason TEXT;
ALTER TABLE model_run ADD COLUMN promotion_decision TEXT;

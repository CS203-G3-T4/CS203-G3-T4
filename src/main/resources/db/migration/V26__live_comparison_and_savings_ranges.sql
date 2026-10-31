-- Prospective evidence only: never backfill research outcomes into these tables.
CREATE TABLE live_forecast_trial (
    id TEXT PRIMARY KEY,
    started_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL CHECK (ends_at > started_at),
    model_version TEXT NOT NULL,
    model_sha256 TEXT NOT NULL,
    policy JSONB NOT NULL
);
CREATE TABLE live_forecast_issue (
    run_id TEXT PRIMARY KEY REFERENCES forecast_run(id),
    trial_id TEXT NOT NULL REFERENCES live_forecast_trial(id),
    captured_at TIMESTAMPTZ NOT NULL,
    eligible BOOLEAN NOT NULL,
    reason TEXT
);
CREATE TABLE live_forecast_price (
    run_id TEXT NOT NULL REFERENCES live_forecast_issue(run_id),
    horizon INT NOT NULL CHECK (horizon BETWEEN 1 AND 24),
    target_period TIMESTAMPTZ NOT NULL,
    ai NUMERIC NOT NULL,
    b3 NUMERIC NOT NULL,
    threshold NUMERIC,
    actual NUMERIC,
    truth_revision BIGINT REFERENCES market_price_revision(id),
    evaluated_at TIMESTAMPTZ,
    PRIMARY KEY (run_id,horizon)
);
CREATE TABLE live_savings_opportunity (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    trial_id TEXT NOT NULL REFERENCES live_forecast_trial(id),
    run_id TEXT NOT NULL REFERENCES live_forecast_issue(run_id),
    -- Frozen identifiers/configuration, retained after operational appliance edits/deletion.
    household_id BIGINT NOT NULL,
    appliance_id BIGINT NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    usual_start TIMESTAMPTZ NOT NULL,
    ai_start TIMESTAMPTZ NOT NULL,
    b3_start TIMESTAMPTZ NOT NULL,
    power_kw NUMERIC NOT NULL CHECK (power_kw > 0),
    run_minutes INT NOT NULL CHECK (run_minutes BETWEEN 1 AND 1440),
    weight NUMERIC NOT NULL CHECK (weight > 0),
    bucket TEXT NOT NULL,
    prediction JSONB NOT NULL,
    outcome JSONB,
    evaluated_at TIMESTAMPTZ,
    UNIQUE (trial_id,household_id,appliance_id,usual_start)
);
CREATE INDEX live_savings_pending ON live_savings_opportunity (issued_at) WHERE outcome IS NULL;
CREATE TABLE recommendation_savings_evidence (
    recommendation_id BIGINT PRIMARY KEY REFERENCES recommendation(id) ON DELETE CASCADE,
    household_id BIGINT NOT NULL REFERENCES household(id) ON DELETE CASCADE,
    forecast_run_id TEXT NOT NULL REFERENCES forecast_run(id),
    model_version TEXT NOT NULL,
    method TEXT NOT NULL CHECK (method IN ('AI','B3')),
    proposal JSONB NOT NULL,
    acceptance JSONB,
    latest_check JSONB
);
CREATE TABLE savings_change_alert (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    recommendation_id BIGINT NOT NULL REFERENCES recommendation(id) ON DELETE CASCADE,
    household_id BIGINT NOT NULL REFERENCES household(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    direction TEXT NOT NULL CHECK (direction IN ('LOWER','HIGHER')),
    message TEXT NOT NULL,
    saving_sgd NUMERIC NOT NULL,
    lower_sgd NUMERIC NOT NULL,
    upper_sgd NUMERIC NOT NULL,
    acknowledged_at TIMESTAMPTZ,
    resolved_at TIMESTAMPTZ,
    UNIQUE (recommendation_id,direction)
);
CREATE INDEX savings_alert_household ON savings_change_alert(household_id,created_at);

-- Capture diagnostics only. Existing trial/model/policy and issued plans stay unchanged.
CREATE TABLE live_capture_exclusion (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES live_forecast_issue(run_id),
    household_id BIGINT,
    appliance_id BIGINT,
    reason TEXT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX live_capture_exclusion_run ON live_capture_exclusion(run_id);

-- F4 (CSDT4-53): the appliance's power and run length when the resident accepted a suggestion, so
-- the realised saving is worked out for the run they agreed to even if the appliance is edited later.
-- Suggestions accepted before this have none and fall back to the appliance's current settings.
ALTER TABLE recommendation
    ADD COLUMN run_power_kw NUMERIC(8, 3) CHECK (run_power_kw > 0),
    ADD COLUMN run_minutes INT CHECK (run_minutes BETWEEN 1 AND 1440);

-- Realised savings look up a household's accepted suggestions by the time the resident chose.
CREATE INDEX idx_recommendation_accepted
    ON recommendation (household_id, accepted_start) WHERE status = 'ACCEPTED';

-- F4 (CSDT4-38): "avoid this time" warnings when a price spike is forecast during an
-- appliance's usual run, with "Got it" (ACKNOWLEDGED) and "Remind me later" (snoozed_until).

-- A warning is kind AVOID. suggested_start/suggested_cost are the spike-free alternative and
-- est_saving is the cost avoided by using it instead of the usual start.
ALTER TABLE recommendation DROP CONSTRAINT recommendation_kind_check;
ALTER TABLE recommendation ADD CONSTRAINT recommendation_kind_check
    CHECK (kind IN ('SHIFT_START', 'AVOID'));

-- ACKNOWLEDGED: the resident pressed "Got it"; that run is not warned about again.
ALTER TABLE recommendation DROP CONSTRAINT recommendation_status_check;
ALTER TABLE recommendation ADD CONSTRAINT recommendation_status_check
    CHECK (status IN ('ACTIVE', 'ACCEPTED', 'DISMISSED', 'ACKNOWLEDGED', 'SUPERSEDED', 'EXPIRED'));
ALTER TABLE recommendation DROP CONSTRAINT ck_recommendation_decided;
ALTER TABLE recommendation ADD CONSTRAINT ck_recommendation_decided
    CHECK (status NOT IN ('ACCEPTED', 'DISMISSED', 'ACKNOWLEDGED') OR decided_at IS NOT NULL);

-- spike_start: the first spike half-hour the usual run touches (warnings only).
-- snoozed_until: "Remind me later"; the warning stays open but is hidden until then.
ALTER TABLE recommendation ADD COLUMN spike_start TIMESTAMPTZ;
ALTER TABLE recommendation ADD COLUMN snoozed_until TIMESTAMPTZ;
ALTER TABLE recommendation ADD CONSTRAINT ck_recommendation_avoid_spike
    CHECK ((kind = 'AVOID') = (spike_start IS NOT NULL));
ALTER TABLE recommendation ADD CONSTRAINT ck_recommendation_snooze
    CHECK (snoozed_until IS NULL OR kind = 'AVOID');

ALTER TABLE recommendation_event DROP CONSTRAINT recommendation_event_event_type_check;
ALTER TABLE recommendation_event ADD CONSTRAINT recommendation_event_event_type_check
    CHECK (event_type IN ('CREATED', 'ACCEPTED', 'DISMISSED', 'ACKNOWLEDGED', 'SNOOZED', 'SUPERSEDED',
                          'EXPIRED'));

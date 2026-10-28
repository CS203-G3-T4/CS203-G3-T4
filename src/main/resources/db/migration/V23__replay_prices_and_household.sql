CREATE TABLE replay_price (
    replay_day DATE NOT NULL,
    interval_start TIMESTAMPTZ NOT NULL,
    usep_sgd_per_mwh NUMERIC(14, 4) NOT NULL,
    forecast_demand_mw NUMERIC(14, 3) NOT NULL,
    PRIMARY KEY (replay_day, interval_start)
);

CREATE INDEX idx_replay_price_interval
    ON replay_price (replay_day, interval_start);

INSERT INTO household (name, dwelling_type, occupants, plan_type, fixed_rate_cents_per_kwh,
                       archetype_id, simulated, created_at, updated_at)
SELECT 'Replay Demo Household', 'HDB_4_ROOM', 3, 'PRICE_LINKED', NULL,
       (SELECT id FROM load_archetype WHERE dwelling_type = 'HDB_4_ROOM' ORDER BY id LIMIT 1),
       TRUE, now(), now()
WHERE NOT EXISTS (SELECT 1 FROM household WHERE name = 'Replay Demo Household' AND simulated = TRUE);

INSERT INTO appliance (household_id, name, type, power_kw, run_minutes, flexibility,
                       earliest_start, must_finish_by, usual_start, runs_per_week, enabled,
                       notes, created_at, updated_at)
SELECT h.id, a.name, a.type, a.power_kw, a.run_minutes, 'FLEXIBLE',
       a.earliest_start, a.must_finish_by, a.usual_start, a.runs_per_week, TRUE,
       'Dedicated replay household', now(), now()
FROM household h
CROSS JOIN (VALUES
    ('Washing Machine', 'WASHING_MACHINE', 0.600, 70, TIME '07:00', TIME '20:00', TIME '18:30', 4),
    ('Tumble Dryer', 'TUMBLE_DRYER', 2.500, 60, TIME '07:00', TIME '21:00', TIME '19:45', 3),
    ('Water Heater', 'WATER_HEATER', 3.000, 30, TIME '00:00', TIME '00:00', TIME '07:00', 7)
) AS a (name, type, power_kw, run_minutes, earliest_start, must_finish_by, usual_start, runs_per_week)
WHERE h.name = 'Replay Demo Household' AND h.simulated = TRUE
  AND NOT EXISTS (
      SELECT 1 FROM appliance WHERE household_id = h.id AND appliance.name = a.name
  );

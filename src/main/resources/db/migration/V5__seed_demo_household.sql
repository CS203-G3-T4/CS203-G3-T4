-- One demo household so the Appliances page has something to show on a fresh database.
-- It mirrors the mockup's "Tan Household" with the units fixed (kW + minutes, not kWh).
-- The fuller edge-case seed set belongs to CSDT4-31; replace or extend this there.

INSERT INTO household (name, dwelling_type, occupants, plan_type, fixed_rate_cents_per_kwh,
                       created_at, updated_at)
VALUES ('The Tan Household', 'HDB_4_ROOM', 4, 'PRICE_LINKED', NULL, now(), now());

INSERT INTO appliance (household_id, name, type, power_kw, run_minutes, flexibility,
                       earliest_start, must_finish_by, usual_start, runs_per_week, enabled,
                       notes, created_at, updated_at)
SELECT h.id, a.name, a.type, a.power_kw, a.run_minutes, a.flexibility,
       a.earliest_start, a.must_finish_by, a.usual_start, a.runs_per_week, a.enabled,
       a.notes, now(), now()
FROM household h
CROSS JOIN (VALUES
    ('Washing Machine', 'WASHING_MACHINE', 0.600, 70, 'FLEXIBLE',
        TIME '07:00', TIME '20:00', TIME '18:30', 4, TRUE, NULL),
    ('Tumble Dryer', 'TUMBLE_DRYER', 2.500, 60, 'FLEXIBLE',
        TIME '07:00', TIME '21:00', TIME '19:45', 3, TRUE, 'Must finish before 9 PM'),
    ('Water Heater', 'WATER_HEATER', 3.000, 30, 'FLEXIBLE',
        TIME '00:00', TIME '00:00', TIME '07:00', 7, TRUE, 'Any time of day'),
    ('EV Charger', 'EV_CHARGER', 7.200, 180, 'FLEXIBLE',
        TIME '00:00', TIME '07:00', TIME '00:00', 3, TRUE, 'Overnight only'),
    ('Living Room Aircon', 'AIRCON', 1.200, 240, 'FIXED',
        NULL, NULL, TIME '18:00', 7, TRUE, NULL),
    ('Refrigerator', 'REFRIGERATOR', 0.150, 1440, 'NOT_FLEXIBLE',
        NULL, NULL, NULL, 7, TRUE, 'Runs all day')
) AS a (name, type, power_kw, run_minutes, flexibility, earliest_start, must_finish_by,
        usual_start, runs_per_week, enabled, notes)
WHERE h.name = 'The Tan Household';

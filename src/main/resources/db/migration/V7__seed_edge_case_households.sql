-- F2 (CSDT4-31): demo households that exercise the edge cases, alongside the Tans (V5).
-- Names match the mockup's admin table. Fixed-rate figures are demo values, not a real tariff.
--   Lim Family       3-room, price-linked, few flexible appliances
--   Ong Household    condo, price-linked, aircon-heavy, dishwasher window crossing midnight
--   Nair Family      landed, price-linked, EV charging overnight (22:00 -> 07:00)
--   Rahman Residence 5-room, FIXED rate: must receive zero shifting advice

INSERT INTO household (name, dwelling_type, occupants, plan_type, fixed_rate_cents_per_kwh,
                       archetype_id, created_at, updated_at)
SELECT v.name, v.dwelling_type, v.occupants, v.plan_type, v.rate, a.id, now(), now()
FROM (VALUES
    ('Lim Family', 'HDB_3_ROOM', 3, 'PRICE_LINKED', NULL::NUMERIC),
    ('Ong Household', 'PRIVATE_APARTMENT_CONDO', 3, 'PRICE_LINKED', NULL::NUMERIC),
    ('Nair Family', 'LANDED', 6, 'PRICE_LINKED', NULL::NUMERIC),
    ('Rahman Residence', 'HDB_5_ROOM_EXECUTIVE', 5, 'FIXED', 29.5::NUMERIC)
) AS v (name, dwelling_type, occupants, plan_type, rate)
JOIN load_archetype a ON a.dwelling_type = v.dwelling_type;

INSERT INTO appliance (household_id, name, type, power_kw, run_minutes, flexibility,
                       earliest_start, must_finish_by, usual_start, runs_per_week, enabled,
                       notes, created_at, updated_at)
SELECT h.id, v.name, v.type, v.power_kw, v.run_minutes, v.flexibility,
       v.earliest_start, v.must_finish_by, v.usual_start, v.runs_per_week, TRUE,
       v.notes, now(), now()
FROM (VALUES
    ('Lim Family', 'Washing Machine', 'WASHING_MACHINE', 0.500, 60, 'FLEXIBLE',
        TIME '08:00', TIME '22:00', TIME '20:00', 4, NULL),
    ('Lim Family', 'Refrigerator', 'REFRIGERATOR', 0.120, 1440, 'NOT_FLEXIBLE',
        NULL, NULL, NULL, 7, NULL),

    ('Ong Household', 'Bedroom Aircon', 'AIRCON', 0.900, 480, 'FIXED',
        NULL, NULL, TIME '22:30', 7, 'Every night'),
    ('Ong Household', 'Living Room Aircon', 'AIRCON', 1.500, 240, 'FIXED',
        NULL, NULL, TIME '19:00', 7, NULL),
    ('Ong Household', 'Dishwasher', 'DISHWASHER', 1.200, 120, 'FLEXIBLE',
        TIME '20:00', TIME '07:00', TIME '21:00', 5, 'Window crosses midnight'),
    ('Ong Household', 'Washing Machine', 'WASHING_MACHINE', 0.600, 70, 'FLEXIBLE',
        TIME '07:00', TIME '22:00', TIME '19:30', 4, NULL),
    ('Ong Household', 'Refrigerator', 'REFRIGERATOR', 0.150, 1440, 'NOT_FLEXIBLE',
        NULL, NULL, NULL, 7, NULL),

    ('Nair Family', 'EV Charger', 'EV_CHARGER', 7.200, 240, 'FLEXIBLE',
        TIME '22:00', TIME '07:00', TIME '22:00', 4, 'Overnight, crosses midnight'),
    ('Nair Family', 'Water Heater', 'WATER_HEATER', 3.000, 45, 'FLEXIBLE',
        TIME '00:00', TIME '00:00', TIME '06:30', 7, 'Any time of day'),
    ('Nair Family', 'Tumble Dryer', 'TUMBLE_DRYER', 2.500, 60, 'FLEXIBLE',
        TIME '07:00', TIME '21:00', TIME '19:00', 5, NULL),
    ('Nair Family', 'Washing Machine', 'WASHING_MACHINE', 0.700, 70, 'FLEXIBLE',
        TIME '07:00', TIME '21:00', TIME '17:30', 6, NULL),
    ('Nair Family', 'Living Room Aircon', 'AIRCON', 2.000, 300, 'FIXED',
        NULL, NULL, TIME '18:00', 7, NULL),
    ('Nair Family', 'Refrigerator', 'REFRIGERATOR', 0.200, 1440, 'NOT_FLEXIBLE',
        NULL, NULL, NULL, 7, NULL),

    ('Rahman Residence', 'Washing Machine', 'WASHING_MACHINE', 0.600, 70, 'FLEXIBLE',
        TIME '07:00', TIME '22:00', TIME '19:00', 5, NULL),
    ('Rahman Residence', 'Tumble Dryer', 'TUMBLE_DRYER', 2.500, 60, 'FLEXIBLE',
        TIME '07:00', TIME '22:00', TIME '20:30', 4, NULL),
    ('Rahman Residence', 'Refrigerator', 'REFRIGERATOR', 0.150, 1440, 'NOT_FLEXIBLE',
        NULL, NULL, NULL, 7, NULL)
) AS v (household, name, type, power_kw, run_minutes, flexibility, earliest_start,
        must_finish_by, usual_start, runs_per_week, notes)
JOIN household h ON h.name = v.household;

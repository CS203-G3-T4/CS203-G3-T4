-- F2 (CSDT4-31): additional admin portfolio properties that exercise household edge cases.
-- Fixed-rate figures are demo values, not a real tariff.
--   Office 1      3-room, price-linked, few flexible appliances
--   Rented Condo  condo, price-linked, aircon-heavy, dishwasher window crossing midnight
--   Office 2      landed, price-linked, EV charging overnight (22:00 -> 07:00)
--   Office 3      5-room, FIXED rate: must receive zero shifting advice

INSERT INTO household (name, dwelling_type, occupants, plan_type, fixed_rate_cents_per_kwh,
                       archetype_id, created_at, updated_at)
SELECT v.name, v.dwelling_type, v.occupants, v.plan_type, v.rate, a.id, now(), now()
FROM (VALUES
    ('Office 1', 'HDB_3_ROOM', 3, 'PRICE_LINKED', NULL::NUMERIC),
    ('Rental', 'PRIVATE_APARTMENT_CONDO', 3, 'PRICE_LINKED', NULL::NUMERIC),
    ('Office 2', 'LANDED', 6, 'PRICE_LINKED', NULL::NUMERIC),
    ('Office 3', 'HDB_5_ROOM_EXECUTIVE', 5, 'FIXED', 29.5::NUMERIC)
) AS v (name, dwelling_type, occupants, plan_type, rate)
JOIN load_archetype a ON a.dwelling_type = v.dwelling_type;

INSERT INTO appliance (household_id, name, type, power_kw, run_minutes, flexibility,
                       earliest_start, must_finish_by, usual_start, runs_per_week, enabled,
                       notes, created_at, updated_at)
SELECT h.id, v.name, v.type, v.power_kw, v.run_minutes, v.flexibility,
       v.earliest_start, v.must_finish_by, v.usual_start, v.runs_per_week, TRUE,
       v.notes, now(), now()
FROM (VALUES
    ('Office 1', 'Washing Machine', 'WASHING_MACHINE', 0.500, 60, 'FLEXIBLE',
        TIME '08:00', TIME '22:00', TIME '20:00', 4, NULL),
    ('Office 1', 'Refrigerator', 'REFRIGERATOR', 0.120, 1440, 'NOT_FLEXIBLE',
        NULL, NULL, NULL, 7, NULL),

    ('Rental', 'Bedroom Aircon', 'AIRCON', 0.900, 480, 'FIXED',
        NULL, NULL, TIME '22:30', 7, 'Every night'),
    ('Rental', 'Living Room Aircon', 'AIRCON', 1.500, 240, 'FIXED',
        NULL, NULL, TIME '19:00', 7, NULL),
    ('Rental', 'Dishwasher', 'DISHWASHER', 1.200, 120, 'FLEXIBLE',
        TIME '20:00', TIME '07:00', TIME '21:00', 5, 'Window crosses midnight'),
    ('Rental', 'Washing Machine', 'WASHING_MACHINE', 0.600, 70, 'FLEXIBLE',
        TIME '07:00', TIME '22:00', TIME '19:30', 4, NULL),
    ('Rental', 'Refrigerator', 'REFRIGERATOR', 0.150, 1440, 'NOT_FLEXIBLE',
        NULL, NULL, NULL, 7, NULL),

    ('Office 2', 'EV Charger', 'EV_CHARGER', 7.200, 240, 'FLEXIBLE',
        TIME '22:00', TIME '07:00', TIME '22:00', 4, 'Overnight, crosses midnight'),
    ('Office 2', 'Water Heater', 'WATER_HEATER', 3.000, 45, 'FLEXIBLE',
        TIME '00:00', TIME '00:00', TIME '06:30', 7, 'Any time of day'),
    ('Office 2', 'Tumble Dryer', 'TUMBLE_DRYER', 2.500, 60, 'FLEXIBLE',
        TIME '07:00', TIME '21:00', TIME '19:00', 5, NULL),
    ('Office 2', 'Washing Machine', 'WASHING_MACHINE', 0.700, 70, 'FLEXIBLE',
        TIME '07:00', TIME '21:00', TIME '17:30', 6, NULL),
    ('Office 2', 'Living Room Aircon', 'AIRCON', 2.000, 300, 'FIXED',
        NULL, NULL, TIME '18:00', 7, NULL),
    ('Office 2', 'Refrigerator', 'REFRIGERATOR', 0.200, 1440, 'NOT_FLEXIBLE',
        NULL, NULL, NULL, 7, NULL),

    ('Office 3', 'Washing Machine', 'WASHING_MACHINE', 0.600, 70, 'FLEXIBLE',
        TIME '07:00', TIME '22:00', TIME '19:00', 5, NULL),
    ('Office 3', 'Tumble Dryer', 'TUMBLE_DRYER', 2.500, 60, 'FLEXIBLE',
        TIME '07:00', TIME '22:00', TIME '20:30', 4, NULL),
    ('Office 3', 'Refrigerator', 'REFRIGERATOR', 0.150, 1440, 'NOT_FLEXIBLE',
        NULL, NULL, NULL, 7, NULL)
) AS v (household, name, type, power_kw, run_minutes, flexibility, earliest_start,
        must_finish_by, usual_start, runs_per_week, notes)
JOIN household h ON h.name = v.household;

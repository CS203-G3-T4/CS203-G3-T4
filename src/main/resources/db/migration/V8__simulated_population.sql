-- F2 (CSDT4-49): 100 simulated households for the admin view, clearly marked simulated = TRUE.
-- Fully deterministic (no random()), so every laptop and the cloud get identical data.
-- ASSUMPTIONS to refine: the home-type mix only roughly follows Singapore's housing stock,
-- and the 70% price-linked share is a demo choice, not market data.
-- Simulated households must never be used to log in (enforced once login exists, CSDT4-23).

ALTER TABLE household ADD COLUMN simulated BOOLEAN NOT NULL DEFAULT FALSE;

INSERT INTO household (name, dwelling_type, occupants, plan_type, fixed_rate_cents_per_kwh,
                       archetype_id, simulated, created_at, updated_at)
SELECT 'Simulated household ' || lpad(g::TEXT, 3, '0'),
       t.dwelling_type,
       CASE WHEN t.dwelling_type = 'LANDED' THEN 3 + (g * 7) % 4 ELSE 1 + (g * 7) % 5 END,
       CASE WHEN (g * 13) % 10 < 7 THEN 'PRICE_LINKED' ELSE 'FIXED' END,
       CASE WHEN (g * 13) % 10 < 7 THEN NULL ELSE 28.0 + (g % 5) * 0.5 END,
       a.id,
       TRUE,
       now(),
       now()
FROM generate_series(1, 100) AS g
CROSS JOIN LATERAL (
    SELECT CASE
               WHEN (g * 37) % 100 < 6 THEN 'HDB_1_2_ROOM'
               WHEN (g * 37) % 100 < 23 THEN 'HDB_3_ROOM'
               WHEN (g * 37) % 100 < 55 THEN 'HDB_4_ROOM'
               WHEN (g * 37) % 100 < 78 THEN 'HDB_5_ROOM_EXECUTIVE'
               WHEN (g * 37) % 100 < 95 THEN 'PRIVATE_APARTMENT_CONDO'
               ELSE 'LANDED'
           END AS dwelling_type
) AS t
JOIN load_archetype a ON a.dwelling_type = t.dwelling_type
ORDER BY g;

-- Appliances: every home has a fridge and a washing machine; 60% a dryer, 75% a water heater,
-- 10% an EV charger. n is the household's number (1-100) in creation order.
WITH sim AS (
    SELECT id, row_number() OVER (ORDER BY id) AS n
    FROM household
    WHERE simulated
)
INSERT INTO appliance (household_id, name, type, power_kw, run_minutes, flexibility,
                       earliest_start, must_finish_by, usual_start, runs_per_week, enabled,
                       notes, created_at, updated_at)
SELECT sim.id, v.name, v.type, v.power_kw, v.run_minutes, v.flexibility,
       v.earliest_start, v.must_finish_by, v.usual_start, v.runs_per_week, TRUE,
       'Simulated', now(), now()
FROM sim
CROSS JOIN LATERAL (VALUES
    ('Refrigerator', 'REFRIGERATOR', 0.150, 1440, 'NOT_FLEXIBLE',
        NULL::TIME, NULL::TIME, NULL::TIME, 7, TRUE),
    ('Washing Machine', 'WASHING_MACHINE', 0.600, 70, 'FLEXIBLE',
        TIME '07:00', TIME '22:00', (TIME '18:00' + (sim.n % 5) * INTERVAL '30 minutes'),
        3 + (sim.n % 4)::INT, TRUE),
    ('Tumble Dryer', 'TUMBLE_DRYER', 2.500, 60, 'FLEXIBLE',
        TIME '07:00', TIME '22:00', (TIME '19:00' + (sim.n % 4) * INTERVAL '30 minutes'),
        2 + (sim.n % 3)::INT, sim.n % 5 < 3),
    ('Water Heater', 'WATER_HEATER', 3.000, 30, 'FLEXIBLE',
        TIME '00:00', TIME '00:00', TIME '07:00', 7, sim.n % 4 <> 0),
    ('EV Charger', 'EV_CHARGER', 7.200, 180, 'FLEXIBLE',
        TIME '22:00', TIME '07:00', TIME '22:30', 3, sim.n % 10 = 0)
) AS v (name, type, power_kw, run_minutes, flexibility, earliest_start, must_finish_by,
        usual_start, runs_per_week, owned)
WHERE v.owned;

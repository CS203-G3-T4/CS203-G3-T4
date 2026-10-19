-- CSDT4-42 (V18; V15-V17 are taken by forecast operations, snooze and tariff_component): residents can turn over-budget alerts on or off. On by default, so setting a budget
-- warns straight away.
ALTER TABLE household_budget ADD COLUMN alerts_enabled BOOLEAN NOT NULL DEFAULT TRUE;

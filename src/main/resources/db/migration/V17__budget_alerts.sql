-- CSDT4-42: residents can turn over-budget alerts on or off. On by default, so setting a budget
-- warns straight away.
ALTER TABLE household_budget ADD COLUMN alerts_enabled BOOLEAN NOT NULL DEFAULT TRUE;

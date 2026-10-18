CREATE TABLE household_budget (
    household_id BIGINT PRIMARY KEY REFERENCES household(id) ON DELETE CASCADE,
    monthly_budget_sgd NUMERIC(10, 2),
    planning_rate_cents_per_kwh NUMERIC(8, 4),
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_monthly_budget_positive CHECK (monthly_budget_sgd IS NULL OR monthly_budget_sgd > 0),
    CONSTRAINT ck_planning_rate_positive CHECK (planning_rate_cents_per_kwh IS NULL OR planning_rate_cents_per_kwh > 0)
);

-- F4 (CSDT4-37): suggestions are recomputed in the background, not on page load, so the pages
-- need the outcome of the latest recompute per household: when it ran, which forecast it used,
-- and why there are no suggestions (fixed-rate plan, no usable forecast, stale price feed).
CREATE TABLE recommendation_refresh (
    household_id BIGINT PRIMARY KEY REFERENCES household (id) ON DELETE CASCADE,
    refreshed_at TIMESTAMPTZ NOT NULL,
    forecast_source VARCHAR(30) CHECK (forecast_source IN ('DEMO_FORECAST', 'F3_FORECAST')),
    forecast_model VARCHAR(60),
    message VARCHAR(300)
);

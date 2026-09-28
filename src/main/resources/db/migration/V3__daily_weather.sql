CREATE TABLE daily_weather (
    forecast_date DATE PRIMARY KEY,
    issued_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    valid_start TIMESTAMPTZ NOT NULL,
    valid_end TIMESTAMPTZ NOT NULL CHECK (valid_end > valid_start),
    temperature_high_c NUMERIC(5, 2) NOT NULL,
    temperature_low_c NUMERIC(5, 2) NOT NULL,
    forecast TEXT NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL,
    CHECK (temperature_high_c >= temperature_low_c)
);

-- Canonical revisions keep publication and actual availability separate.
CREATE TABLE market_price_revision (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source TEXT NOT NULL,
    period_start TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    available_at TIMESTAMPTZ,
    usep NUMERIC(14,4) NOT NULL,
    demand NUMERIC(14,3),
    vcp NUMERIC(14,4),
    price_status TEXT NOT NULL CHECK (price_status IN ('PROVISIONAL','FINAL')),
    time_mapping TEXT NOT NULL,
    CHECK ((published_at IS NULL) = (available_at IS NULL)),
    CHECK (extract(epoch FROM period_start)::bigint % 1800 = 0),
    UNIQUE NULLS NOT DISTINCT (source,period_start,published_at,usep,demand,vcp,price_status,time_mapping)
);
CREATE INDEX idx_market_revision_asof ON market_price_revision (period_start,available_at,published_at);
CREATE TABLE market_revision_origin (
    origin TEXT NOT NULL,
    external_id TEXT NOT NULL,
    revision_id BIGINT NOT NULL REFERENCES market_price_revision(id),
    document JSONB NOT NULL,
    PRIMARY KEY (origin,external_id)
);

-- Seed only metadata actually preserved by F1; no fabricated earlier availability.
INSERT INTO market_price_revision(source,period_start,published_at,available_at,usep,demand,vcp,price_status,time_mapping)
SELECT 'NEMS_SN_SG',to_timestamp(floor(extract(epoch FROM source_updated_at)/1800)*1800),
       source_updated_at,first_collected_at,(payload->>'usep')::numeric,
       (payload->>'demand')::numeric,(payload->>'vcp')::numeric,'PROVISIONAL','UNVERIFIED_FLOOR'
FROM market_observation ON CONFLICT DO NOTHING;

CREATE TABLE daily_weather_revision (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    forecast_date DATE NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    available_at TIMESTAMPTZ NOT NULL,
    valid_start TIMESTAMPTZ NOT NULL,
    valid_end TIMESTAMPTZ NOT NULL CHECK(valid_end > valid_start),
    temperature_high_c NUMERIC NOT NULL,
    temperature_low_c NUMERIC NOT NULL,
    payload JSONB NOT NULL,
    UNIQUE (forecast_date,issued_at,updated_at,payload)
);
INSERT INTO daily_weather_revision(forecast_date,issued_at,updated_at,available_at,valid_start,valid_end,
                                   temperature_high_c,temperature_low_c,payload)
SELECT forecast_date,issued_at,updated_at,fetched_at,valid_start,valid_end,temperature_high_c,temperature_low_c,payload
FROM daily_weather;

CREATE TABLE model_run (
    version TEXT PRIMARY KEY,
    manifest JSONB NOT NULL,
    registered_at TIMESTAMPTZ NOT NULL,
    usable_from TIMESTAMPTZ NOT NULL,
    promotion_state TEXT NOT NULL
);
CREATE TABLE forecast_run (
    id TEXT PRIMARY KEY,
    sequence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    as_of TIMESTAMPTZ NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL,
    origin_slot TIMESTAMPTZ NOT NULL,
    input_revision TEXT NOT NULL,
    input_snapshot JSONB NOT NULL,
    mode TEXT NOT NULL CHECK(mode IN ('LIVE','REPLAY')),
    model_type TEXT NOT NULL CHECK(model_type IN ('AI','BASELINE','NONE')),
    model_version TEXT NOT NULL,
    selected_model TEXT NOT NULL,
    fallback_reason TEXT,
    quality_flags JSONB NOT NULL,
    stale BOOLEAN NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('AVAILABLE','UNAVAILABLE')),
    UNIQUE(mode,origin_slot,input_revision,model_version)
);
CREATE INDEX idx_forecast_latest ON forecast_run(mode,as_of DESC,generated_at DESC);
CREATE TABLE forecast_point (
    run_id TEXT NOT NULL REFERENCES forecast_run(id),
    model_identifier TEXT NOT NULL,
    horizon INTEGER NOT NULL CHECK(horizon BETWEEN 1 AND 24),
    target_period TIMESTAMPTZ NOT NULL,
    predicted_usep NUMERIC NOT NULL,
    spike_threshold NUMERIC,
    spike_flag BOOLEAN,
    PRIMARY KEY(run_id,model_identifier,horizon),
    UNIQUE(run_id,model_identifier,target_period)
);
CREATE TABLE forecast_evaluation (
    run_id TEXT NOT NULL,
    model_identifier TEXT NOT NULL,
    horizon INTEGER NOT NULL,
    truth_policy TEXT NOT NULL,
    truth_revision BIGINT NOT NULL REFERENCES market_price_revision(id),
    actual_usep NUMERIC NOT NULL,
    evaluated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(run_id,model_identifier,horizon,truth_policy),
    FOREIGN KEY(run_id,model_identifier,horizon) REFERENCES forecast_point(run_id,model_identifier,horizon)
);

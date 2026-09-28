-- Run against an isolated migrated PostgreSQL database with psql -v ON_ERROR_STOP=1.
BEGIN;
INSERT INTO market_observation (source_updated_at, first_collected_at, last_seen_at,
    source_age_seconds_at_collection, feed_stale_at_collection, payload)
VALUES ('2026-09-23 15:01:00+08', '2026-09-23 15:31:42+08', '2026-09-23 15:31:42+08',
    1842, false, '{"updated":1790146860,"usep":286.14,"demand":7478.0,"vcp":252.68}');
DO $$ BEGIN
    ASSERT (SELECT weather_match_status = 'no_matching_weather' FROM combined_observations);
END $$;
INSERT INTO weather_observation (issued_at, updated_at, valid_start, valid_end,
    first_collected_at, last_seen_at, item)
VALUES ('2026-09-23 14:30+08', '2026-09-23 14:36:31+08', '2026-09-23 14:30+08',
    '2026-09-23 16:30+08', now(), now(), '{"forecasts":[{"area":"Ang Mo Kio","forecast":"Cloudy"}]}'),
    ('2026-09-23 15:00+08', '2026-09-23 15:02+08', '2026-09-23 15:00+08',
    '2026-09-23 17:00+08', now(), now(), '{"forecasts":[{"area":"Ang Mo Kio","forecast":"Future forecast"}]}');
DO $$ BEGIN
    ASSERT (SELECT weather_match_status = 'matched_as_of_source_update'
        AND "weather_Ang_Mo_Kio" = 'Cloudy' AND source_updated_sgt = '2026-09-23T15:01:00+08:00'
        AND source_updated_unix = 1790146860 AND usep_sgd_per_mwh = 286.14
        AND demand_forecast_mw = 7478 AND vcp_sgd_per_mwh = 252.68
        AND length(record_id) = 64 FROM combined_observations);
END $$;
INSERT INTO market_observation (source_updated_at, first_collected_at, last_seen_at,
    source_age_seconds_at_collection, feed_stale_at_collection, payload)
VALUES ('2026-09-23 15:01+08', '2026-09-23 16:01+08', '2026-09-23 16:01+08',
    3600, true, '{"updated":1790146860,"usep":286.14,"demand":7478.0,"vcp":252.68}')
ON CONFLICT (payload) DO UPDATE SET last_seen_at = EXCLUDED.last_seen_at;
DO $$ BEGIN
    ASSERT (SELECT count(*) = 1 FROM market_observation);
    ASSERT (SELECT first_collected_sgt = '2026-09-23T15:31:42+08:00'
        AND last_seen_sgt = '2026-09-23T16:01:00+08:00'
        AND NOT feed_stale_at_collection FROM combined_observations);
END $$;
ROLLBACK;

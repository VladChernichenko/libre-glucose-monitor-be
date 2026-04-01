-- Replace fixed 100 rows per user with full history keyed by Nightscout id or reading time.

ALTER TABLE nightscout_chart_data DROP CONSTRAINT IF EXISTS uk_nightscout_chart_data_user_row;

DROP INDEX IF EXISTS idx_nightscout_chart_data_user_row;

-- Keep one row per (user_id, nightscout_id) when id is present
WITH d AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY user_id, nightscout_id
               ORDER BY last_updated DESC NULLS LAST, created_at DESC, id
           ) AS rn
    FROM nightscout_chart_data
    WHERE nightscout_id IS NOT NULL AND btrim(nightscout_id) <> ''
)
DELETE FROM nightscout_chart_data n
WHERE n.id IN (SELECT id FROM d WHERE rn > 1);

-- Keep one row per (user_id, date_timestamp) when Nightscout id is missing
WITH d AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY user_id, date_timestamp
               ORDER BY last_updated DESC NULLS LAST, created_at DESC, id
           ) AS rn
    FROM nightscout_chart_data
    WHERE nightscout_id IS NULL OR btrim(nightscout_id) = ''
)
DELETE FROM nightscout_chart_data n
WHERE n.id IN (SELECT id FROM d WHERE rn > 1);

ALTER TABLE nightscout_chart_data DROP COLUMN IF EXISTS row_index;

CREATE UNIQUE INDEX IF NOT EXISTS uk_nightscout_chart_user_nsid
    ON nightscout_chart_data (user_id, nightscout_id)
    WHERE nightscout_id IS NOT NULL AND btrim(nightscout_id) <> '';

CREATE UNIQUE INDEX IF NOT EXISTS uk_nightscout_chart_user_ts
    ON nightscout_chart_data (user_id, date_timestamp)
    WHERE nightscout_id IS NULL OR btrim(nightscout_id) = '';

CREATE INDEX IF NOT EXISTS idx_nightscout_chart_user_date ON nightscout_chart_data (user_id, date_timestamp);

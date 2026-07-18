-- Forward migration for existing DBs (V1/V7 already applied without night ISF).
-- Idempotent: safe if columns/constraints were applied manually.

ALTER TABLE user_settings
    ADD COLUMN IF NOT EXISTS isf_night DOUBLE PRECISION;

ALTER TABLE user_settings
    DROP CONSTRAINT IF EXISTS chk_user_settings_isf_night_positive;

ALTER TABLE user_settings
    ADD CONSTRAINT chk_user_settings_isf_night_positive
        CHECK (isf_night IS NULL OR isf_night > 0);

COMMENT ON COLUMN user_settings.isf_night IS
    'Manual ISF override for 22:00-05:00; NULL = use autotuned isf';

ALTER TABLE isf_meal_window_snapshots
    DROP CONSTRAINT IF EXISTS chk_isf_meal_window;

ALTER TABLE isf_meal_window_snapshots
    ADD CONSTRAINT chk_isf_meal_window
        CHECK (meal_window IN ('BREAKFAST', 'LUNCH', 'DINNER', 'NIGHT'));

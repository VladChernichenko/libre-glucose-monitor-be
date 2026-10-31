-- Cached per-user, per-meal-window observational ISF estimates.
-- Recomputed daily by IsfMealWindowScheduler and on-demand when a bolus is logged.
-- Each row is one (user_id, meal_window) pair - at most 4 rows per user
-- (BREAKFAST, LUNCH, DINNER, NIGHT) covering the full day.

CREATE TABLE IF NOT EXISTS isf_meal_window_snapshots (
    id                  UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID             NOT NULL,
    meal_window         VARCHAR(20)      NOT NULL,
    isf_mmol_per_u      DOUBLE PRECISION,
    weighted_samples    DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    raw_sample_count    INTEGER          NOT NULL DEFAULT 0,
    last_updated        TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_isf_meal_window_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_isf_meal_window     CHECK (meal_window IN ('BREAKFAST','LUNCH','DINNER','NIGHT')),
    CONSTRAINT uq_isf_meal_window_user UNIQUE (user_id, meal_window)
);

CREATE INDEX IF NOT EXISTS idx_isf_meal_window_user ON isf_meal_window_snapshots(user_id);

COMMENT ON TABLE isf_meal_window_snapshots IS
    'Per-user, per-meal-window observational ISF estimates. Refreshed daily and on every new bolus event.';
COMMENT ON COLUMN isf_meal_window_snapshots.isf_mmol_per_u IS
    'Insulin Sensitivity Factor - mmol/L drop per 1 unit of rapid-acting insulin. NULL when insufficient data.';
COMMENT ON COLUMN isf_meal_window_snapshots.weighted_samples IS
    'Sum of per-event weights. Correction-only boluses contribute 1.0; meal-attached boluses contribute 0.4.';
COMMENT ON COLUMN isf_meal_window_snapshots.raw_sample_count IS
    'Raw count of bolus events that contributed (regardless of weight).';

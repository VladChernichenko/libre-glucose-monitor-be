-- Cached per-user, per-meal-window insulin-to-carb ratios learned from clean meals.
-- Recomputed daily by IsfMealWindowScheduler and by POST /api/isf/meal-windows/recompute.
-- One row per (user_id, meal_window) - at most 4 rows per user (BREAKFAST, LUNCH, DINNER, NIGHT).
-- A learned value lives only here; it reaches user_settings only when the user accepts a suggestion.

CREATE TABLE IF NOT EXISTS carb_ratio_meal_window_snapshots (
    id               UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID             NOT NULL,
    meal_window      VARCHAR(20)      NOT NULL,
    grams_per_unit   DOUBLE PRECISION,
    sample_count     INTEGER          NOT NULL DEFAULT 0,
    computed_at      TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_carb_ratio_meal_window_user  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_carb_ratio_meal_window      CHECK (meal_window IN ('BREAKFAST','LUNCH','DINNER','NIGHT')),
    CONSTRAINT chk_carb_ratio_grams_per_unit   CHECK (grams_per_unit IS NULL OR grams_per_unit BETWEEN 3 AND 30),
    CONSTRAINT uq_carb_ratio_meal_window_user  UNIQUE (user_id, meal_window)
);

CREATE INDEX IF NOT EXISTS idx_carb_ratio_meal_window_user ON carb_ratio_meal_window_snapshots(user_id);

COMMENT ON TABLE carb_ratio_meal_window_snapshots IS
    'Per-user, per-meal-window insulin-to-carb ratio learned from clean meals in the last 14 days.';
COMMENT ON COLUMN carb_ratio_meal_window_snapshots.grams_per_unit IS
    'Median grams of carbohydrate per unit over the clean meals. NULL when fewer than 3 meals.';
COMMENT ON COLUMN carb_ratio_meal_window_snapshots.sample_count IS
    'Number of clean meals that gave an estimate, including when too few to expose a ratio.';

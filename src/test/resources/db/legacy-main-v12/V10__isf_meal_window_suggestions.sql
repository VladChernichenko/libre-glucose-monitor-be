-- Cadence state for the morning per-meal-window ISF suggestion banner.
-- Shown at most once every 3 days when observational + twin data are ready.

CREATE TABLE IF NOT EXISTS isf_meal_window_suggestions (
    user_id             UUID             PRIMARY KEY,
    last_accepted_at    TIMESTAMPTZ,
    last_dismissed_at   TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_isf_suggestion_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
COMMENT ON TABLE isf_meal_window_suggestions IS
    'Per-user accept/dismiss timestamps for the ISF meal-window suggestion banner (3-day cadence).';

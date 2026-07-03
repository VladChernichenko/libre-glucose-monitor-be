-- Per-user "digital twin": machine-learned corrections to the Hovorka glucose prediction model,
-- fitted nightly by DigitalTwinCalibrationScheduler from the user's own predicted-vs-actual CGM
-- history. Exactly one row per user. Applied to PREDICTIONS ONLY — it never modifies the user's
-- insulin-dosing settings (user_settings.isf / carb_ratio).

CREATE TABLE IF NOT EXISTS user_digital_twin (
    id                UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID             NOT NULL,

    -- Learned multiplicative scales on the physiological parameters (1.0 = no correction).
    isf_scale         DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    ag_scale          DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    tmax_g_scale      DOUBLE PRECISION NOT NULL DEFAULT 1.0,  -- reserved (not yet wired to live ODE)
    egp_scale         DOUBLE PRECISION NOT NULL DEFAULT 1.0,  -- reserved (residual layer covers drift)

    -- Data-driven residual layer: 24 comma-separated per-hour corrections [mmol/L].
    residual_grid     TEXT,

    -- Prediction band: per-horizon residual σ [mmol/L], comma-separated at 30/60/90/120 min.
    uncertainty_sd_grid TEXT,

    -- Whether the calibrated twin is active for this user's predictions (improved out-of-sample).
    applied           BOOLEAN          NOT NULL DEFAULT FALSE,

    -- Fit diagnostics (out-of-sample, from the temporal holdout).
    mae_baseline      DOUBLE PRECISION,
    mae_calibrated    DOUBLE PRECISION,
    improvement_pct   DOUBLE PRECISION,
    train_samples     INTEGER          NOT NULL DEFAULT 0,
    val_samples       INTEGER          NOT NULL DEFAULT 0,
    confidence        VARCHAR(10),
    status            TEXT,

    fitted_at         TIMESTAMPTZ,
    created_at        TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ      NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_user_digital_twin_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_user_digital_twin_user UNIQUE (user_id)
);

CREATE INDEX IF NOT EXISTS idx_user_digital_twin_user ON user_digital_twin(user_id);

COMMENT ON TABLE user_digital_twin IS
    'Per-user learned corrections to the Hovorka prediction model. Applied to predictions only; fitted nightly from predicted-vs-actual CGM error.';
COMMENT ON COLUMN user_digital_twin.isf_scale IS
    'Multiplier on the user''s ISF used in predictions (1.0 = no change). Corrects systematic insulin over/under-response.';
COMMENT ON COLUMN user_digital_twin.ag_scale IS
    'Multiplier on meal magnitude A_G used in predictions (1.0 = no change). Corrects systematic carb-effect bias.';
COMMENT ON COLUMN user_digital_twin.residual_grid IS
    '24 comma-separated per-hour additive corrections [mmol/L], learned from residuals the physiology leaves behind (dawn/activity/fasting drift), with empirical-Bayes shrinkage.';
COMMENT ON COLUMN user_digital_twin.uncertainty_sd_grid IS
    'Per-horizon predictive standard deviation [mmol/L] at 30/60/90/120 min (variance-shrunk, monotone), rendered as the confidence band around each predicted point.';
COMMENT ON COLUMN user_digital_twin.applied IS
    'TRUE when the twin beat the un-calibrated model on the held-out validation window and is active for predictions.';

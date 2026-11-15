-- Personal action curve of one rapid insulin for one user: the peak and duration fitted from the
-- user's own trusted corrections, replacing the catalog values for that insulin in forecasts when
-- `applied`. One unit lowers glucose by the same total amount whatever the curve - this table only
-- reshapes when the effect arrives. The digital twin (user_digital_twin) stays one per user.
CREATE TABLE IF NOT EXISTS user_insulin_curve (
    id            UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID             NOT NULL,
    insulin_code  VARCHAR(32)      NOT NULL,
    peak_minutes  DOUBLE PRECISION NOT NULL,
    dia_hours     DOUBLE PRECISION NOT NULL,
    applied       BOOLEAN          NOT NULL DEFAULT FALSE,
    mae_without   DOUBLE PRECISION,
    mae_with      DOUBLE PRECISION,
    dose_count    INTEGER          NOT NULL DEFAULT 0,
    status        TEXT,
    fitted_at     TIMESTAMPTZ,
    created_at    TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_user_insulin_curve_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_user_insulin_curve UNIQUE (user_id, insulin_code)
);

COMMENT ON TABLE  user_insulin_curve              IS 'Per-user fitted action curve (peak, duration) of one rapid insulin';
COMMENT ON COLUMN user_insulin_curve.insulin_code IS 'insulin_catalog.code this curve personalises';
COMMENT ON COLUMN user_insulin_curve.applied      IS 'True only when the fitted curve beat the catalog curve out-of-sample';
COMMENT ON COLUMN user_insulin_curve.dose_count   IS 'Logged corrections of this insulin in the training window';

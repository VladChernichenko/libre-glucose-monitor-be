-- Frozen forecasts: the dashboard forecast exactly as it was when an anchor note (rapid dose,
-- carbs >= 10 g, or fast sugar / hypo treatment) was saved. The only user-facing
-- prediction-vs-actual record; never back-filled or re-computed.
CREATE TABLE IF NOT EXISTS forecast_snapshot (
    id              UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID             NOT NULL,
    note_id         UUID             NOT NULL,
    made_at         TIMESTAMPTZ      NOT NULL,
    engine          VARCHAR(32)      NOT NULL,
    model_version   VARCHAR(64),
    start_glucose   DOUBLE PRECISION,
    points          JSONB            NOT NULL,
    check60_actual  DOUBLE PRECISION,
    check60_hit     BOOLEAN,
    check120_actual DOUBLE PRECISION,
    check120_hit    BOOLEAN,
    check240_actual DOUBLE PRECISION,
    check240_hit    BOOLEAN,
    complete        BOOLEAN          NOT NULL DEFAULT FALSE,
    excluded        BOOLEAN          NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_forecast_snapshot_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_forecast_snapshot_note UNIQUE (note_id)
);

CREATE INDEX IF NOT EXISTS idx_forecast_snapshot_user_made ON forecast_snapshot(user_id, made_at DESC);

COMMENT ON TABLE forecast_snapshot IS
    'Dashboard forecast frozen at the save of an anchor note. Glucose values in mmol/L.';
COMMENT ON COLUMN forecast_snapshot.points IS
    'JSON array of {"minute": m, "mmol": v}: 5-min forecast points, minutes after made_at, up to +240.';
COMMENT ON COLUMN forecast_snapshot.excluded IS
    'Dose/carbs/time edited more than 15 min after the original save: kept for inspection, not scored.';

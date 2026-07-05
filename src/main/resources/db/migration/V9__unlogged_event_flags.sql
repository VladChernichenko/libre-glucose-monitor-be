-- Unlogged-event detector: windows where glucose moved in a way the logged inputs (COB/IOB) do not
-- explain — probable unlogged or under-estimated food/insulin. Feeds user confirmation + calibration
-- down-weighting.

CREATE TABLE IF NOT EXISTS unlogged_event_flags (
    id                 UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            UUID             NOT NULL,
    category           VARCHAR(28)      NOT NULL,
    direction          VARCHAR(8)       NOT NULL,
    window_start       TIMESTAMPTZ      NOT NULL,
    window_end         TIMESTAMPTZ      NOT NULL,
    mean_residual_mmol DOUBLE PRECISION NOT NULL,
    sigma_multiple     DOUBLE PRECISION,
    state              VARCHAR(12)      NOT NULL DEFAULT 'OPEN',
    detected_at        TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    resolved_at        TIMESTAMPTZ,
    CONSTRAINT fk_unlogged_flags_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_unlogged_category CHECK (category IN
        ('UNLOGGED_FOOD','UNDER_ESTIMATED_FOOD','UNLOGGED_INSULIN','UNDER_ESTIMATED_INSULIN')),
    CONSTRAINT chk_unlogged_direction CHECK (direction IN ('RISE','FALL')),
    CONSTRAINT chk_unlogged_state     CHECK (state IN ('OPEN','CONFIRMED','DISMISSED'))
);

CREATE INDEX IF NOT EXISTS idx_unlogged_flags_user       ON unlogged_event_flags(user_id);
CREATE INDEX IF NOT EXISTS idx_unlogged_flags_state      ON unlogged_event_flags(state);
CREATE INDEX IF NOT EXISTS idx_unlogged_flags_user_state ON unlogged_event_flags(user_id, state);

COMMENT ON TABLE unlogged_event_flags IS
    'Windows with sustained unexplained residual — probable unlogged/under-estimated food or insulin.';

-- Glycemic events: windows where glucose moved in a way the logged inputs (COB/IOB) do not explain.
-- Originally created as "unlogged_event_flags"; edited in place per the repo's migration rule
-- (existing databases need the one-time reset described in the glycemic-event-engine spec, section 1.1).

CREATE TABLE IF NOT EXISTS glycemic_events (
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
    CONSTRAINT fk_glycemic_events_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_glycemic_category CHECK (category IN
        ('UNLOGGED_FOOD','UNDER_ESTIMATED_FOOD','UNLOGGED_INSULIN','UNDER_ESTIMATED_INSULIN')),
    CONSTRAINT chk_glycemic_direction CHECK (direction IN ('RISE','FALL')),
    CONSTRAINT chk_glycemic_state     CHECK (state IN ('OPEN','CONFIRMED','DISMISSED'))
);

CREATE INDEX IF NOT EXISTS idx_glycemic_events_user       ON glycemic_events(user_id);
CREATE INDEX IF NOT EXISTS idx_glycemic_events_state      ON glycemic_events(state);
CREATE INDEX IF NOT EXISTS idx_glycemic_events_user_state ON glycemic_events(user_id, state);

COMMENT ON TABLE glycemic_events IS
    'Windows with a sustained unexplained residual - probable unlogged/under-estimated inputs or physiology.';

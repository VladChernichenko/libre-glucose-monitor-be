-- Glycemic events: windows where glucose moved in a way the logged inputs (COB/IOB) do not explain.
-- Originally created as "unlogged_event_flags"; edited in place per the repo's migration rule
-- (existing databases need the one-time reset described in the glycemic-event-engine spec, section 1.1).

CREATE TABLE IF NOT EXISTS glycemic_events (
    id                 UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            UUID             NOT NULL,
    category           VARCHAR(28)      NOT NULL,
    detector_category  VARCHAR(28)      NOT NULL,
    user_label         VARCHAR(28),
    direction          VARCHAR(8)       NOT NULL,
    phase              VARCHAR(12)      NOT NULL DEFAULT 'FINAL',
    onset_at           TIMESTAMPTZ      NOT NULL,
    window_start       TIMESTAMPTZ      NOT NULL,
    window_end         TIMESTAMPTZ      NOT NULL,
    resolves_at        TIMESTAMPTZ,
    mean_residual_mmol DOUBLE PRECISION NOT NULL,
    sigma_multiple     DOUBLE PRECISION,
    confidence         DOUBLE PRECISION,
    suppressed         BOOLEAN          NOT NULL DEFAULT FALSE,
    linked_event_id    UUID,
    features           JSONB,
    state              VARCHAR(12)      NOT NULL DEFAULT 'OPEN',
    detected_at        TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    resolved_at        TIMESTAMPTZ,
    version            BIGINT           NOT NULL DEFAULT 0,
    CONSTRAINT fk_glycemic_events_user   FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_glycemic_events_parent FOREIGN KEY (linked_event_id) REFERENCES glycemic_events(id) ON DELETE SET NULL,
    CONSTRAINT chk_glycemic_category CHECK (category IN
        ('UNLOGGED_FOOD','UNDER_ESTIMATED_FOOD','STRESS','ABSORPTION_DELAY','LIPODYSTROPHY','DAWN_PHENOMENON',
         'ANAEROBIC_ACTIVITY','UNLOGGED_INSULIN','UNDER_ESTIMATED_INSULIN','AEROBIC_ACTIVITY')),
    CONSTRAINT chk_glycemic_detector_category CHECK (detector_category IN
        ('UNLOGGED_FOOD','UNDER_ESTIMATED_FOOD','STRESS','ABSORPTION_DELAY','LIPODYSTROPHY','DAWN_PHENOMENON',
         'ANAEROBIC_ACTIVITY','UNLOGGED_INSULIN','UNDER_ESTIMATED_INSULIN','AEROBIC_ACTIVITY')),
    CONSTRAINT chk_glycemic_user_label CHECK (user_label IS NULL OR user_label IN
        ('UNLOGGED_FOOD','UNDER_ESTIMATED_FOOD','STRESS','ABSORPTION_DELAY','LIPODYSTROPHY','DAWN_PHENOMENON',
         'ANAEROBIC_ACTIVITY','UNLOGGED_INSULIN','UNDER_ESTIMATED_INSULIN','AEROBIC_ACTIVITY')),
    CONSTRAINT chk_glycemic_direction CHECK (direction IN ('RISE','FALL')),
    CONSTRAINT chk_glycemic_phase     CHECK (phase IN ('PROVISIONAL','FINAL')),
    CONSTRAINT chk_glycemic_state     CHECK (state IN ('OPEN','CONFIRMED','DISMISSED'))
);

CREATE INDEX IF NOT EXISTS idx_glycemic_events_user       ON glycemic_events(user_id);
CREATE INDEX IF NOT EXISTS idx_glycemic_events_state      ON glycemic_events(state);
CREATE INDEX IF NOT EXISTS idx_glycemic_events_user_state ON glycemic_events(user_id, state);
CREATE INDEX IF NOT EXISTS idx_glycemic_events_user_onset ON glycemic_events(user_id, onset_at);
CREATE INDEX IF NOT EXISTS idx_glycemic_events_user_phase ON glycemic_events(user_id, phase);
CREATE INDEX IF NOT EXISTS idx_glycemic_events_linked     ON glycemic_events(linked_event_id);

COMMENT ON TABLE glycemic_events IS
    'Windows with a sustained unexplained residual - probable unlogged/under-estimated inputs or physiology.';

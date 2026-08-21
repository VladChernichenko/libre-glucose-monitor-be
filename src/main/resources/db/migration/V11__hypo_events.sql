-- Hypo prompt lifecycle: one row per detected sub-threshold glucose window, opened by
-- GlucoseAnomalyDetector and resolved by the user confirming a rescue carb or dismissing.
-- Mirrors unlogged_event_flags so both detect -> prompt -> resolve flows read alike.

CREATE TABLE IF NOT EXISTS hypo_events (
    id                    UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id               UUID             NOT NULL,
    trigger_glucose_mmol  DOUBLE PRECISION NOT NULL,
    state                 VARCHAR(12)      NOT NULL DEFAULT 'OPEN',
    note_id               UUID,
    detected_at           TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    resolved_at           TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_hypo_events_user  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_hypo_events_note  FOREIGN KEY (note_id) REFERENCES notes(id) ON DELETE SET NULL,
    CONSTRAINT chk_hypo_event_state CHECK (state IN ('OPEN','CONFIRMED','DISMISSED','EXPIRED'))
);

CREATE INDEX IF NOT EXISTS idx_hypo_events_user_state ON hypo_events(user_id, state);
CREATE INDEX IF NOT EXISTS idx_hypo_events_detected   ON hypo_events(detected_at);

COMMENT ON TABLE hypo_events IS
    'Detected sub-3.9 mmol/L windows prompting the user to log a fast-acting rescue carb.';
COMMENT ON COLUMN hypo_events.note_id IS
    'The hypo_treatment note created on confirm. ON DELETE SET NULL: deleting the note must not erase the record that a hypo occurred and was prompted.';

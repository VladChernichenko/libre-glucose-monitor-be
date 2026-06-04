-- Real-life verification loop: tracks how closely real meal events match
-- predicted glucose changes using the stored ISF / carbRatio settings.

CREATE TABLE IF NOT EXISTS verification_events (
    id                   UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id              UUID             NOT NULL,
    note_id              UUID             NOT NULL,
    status               VARCHAR(20)      NOT NULL DEFAULT 'PENDING',
    baseline_glucose     DOUBLE PRECISION,
    actual_glucose_2h    DOUBLE PRECISION,
    predicted_delta      DOUBLE PRECISION,
    actual_delta         DOUBLE PRECISION,
    error                DOUBLE PRECISION,
    relative_error_pct   DOUBLE PRECISION,
    skip_reason          TEXT,
    evaluated_at         TIMESTAMPTZ,
    created_at           TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_verif_events_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_verif_events_note FOREIGN KEY (note_id) REFERENCES notes(id) ON DELETE CASCADE,
    CONSTRAINT chk_verif_status     CHECK (status IN ('PENDING','ELIGIBLE','SKIPPED','COMPLETED'))
);

-- Rolling accuracy summary per user, refreshed after each completed verification event.
CREATE TABLE IF NOT EXISTS verification_summary (
    user_id              UUID             PRIMARY KEY,
    n_events             INTEGER          NOT NULL DEFAULT 0,
    mean_error           DOUBLE PRECISION,
    consistency_score    DOUBLE PRECISION,
    suggested_isf        DOUBLE PRECISION,
    suggested_carb_ratio DOUBLE PRECISION,
    suggestion_ready     BOOLEAN          NOT NULL DEFAULT FALSE,
    last_updated         TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_verif_summary_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_verif_events_user    ON verification_events(user_id);
CREATE INDEX IF NOT EXISTS idx_verif_events_note    ON verification_events(note_id);
CREATE INDEX IF NOT EXISTS idx_verif_events_status  ON verification_events(status);
CREATE INDEX IF NOT EXISTS idx_verif_events_created ON verification_events(created_at);

COMMENT ON TABLE verification_events IS
    'Per-note verification events comparing predicted vs actual glucose response.';
COMMENT ON TABLE verification_summary IS
    'Rolling accuracy summary per user; suggestion_ready = true triggers a refinement prompt.';

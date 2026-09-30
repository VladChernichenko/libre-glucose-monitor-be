-- Append-only audit trail of every state/category change on a glycemic event (spec section 1.2).
-- Rows are never updated or deleted by application code.

CREATE TABLE IF NOT EXISTS glycemic_event_history (
    id              UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id        UUID             NOT NULL,
    user_id         UUID             NOT NULL,
    at              TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    kind            VARCHAR(12)      NOT NULL,
    category_before VARCHAR(28),
    category_after  VARCHAR(28),
    confidence      DOUBLE PRECISION,
    actor           VARCHAR(8)       NOT NULL,
    reason          VARCHAR(24),
    CONSTRAINT fk_glycemic_history_event FOREIGN KEY (event_id) REFERENCES glycemic_events(id) ON DELETE CASCADE,
    CONSTRAINT chk_glycemic_history_kind  CHECK (kind IN
        ('DETECTED','UPDATED','RESOLVED','CONFIRMED','RELABELLED','DISMISSED')),
    CONSTRAINT chk_glycemic_history_actor CHECK (actor IN ('SYSTEM','USER'))
);

CREATE INDEX IF NOT EXISTS idx_glycemic_history_event ON glycemic_event_history(event_id, at);

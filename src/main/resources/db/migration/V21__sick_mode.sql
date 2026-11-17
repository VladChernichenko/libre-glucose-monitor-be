-- Sick mode: time-based illness segments (one open segment per user) and the user's answers to
-- "stubborn high" severity step-up proposals. Timestamps are the user's wall clock, like notes.timestamp.

CREATE TABLE IF NOT EXISTS sick_segment (
    id                  UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID             NOT NULL,
    episode_id          UUID             NOT NULL,
    severity            VARCHAR(24)      NOT NULL,
    started_at          TIMESTAMPTZ      NOT NULL,
    ended_at            TIMESTAMPTZ,
    last_confirmed_at   TIMESTAMPTZ      NOT NULL,
    declared_at         TIMESTAMPTZ      NOT NULL, -- user wall clock when the segment was created; never backdated
    end_reason          VARCHAR(16),     -- USER, MORNING_NO, LAPSED, SEVERITY_CHANGE
    healthy_basal_units DOUBLE PRECISION,
    created_at          TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ      NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_sick_segment_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_sick_segment_user_started ON sick_segment(user_id, started_at);
CREATE UNIQUE INDEX IF NOT EXISTS uq_sick_segment_one_open ON sick_segment(user_id) WHERE ended_at IS NULL;

CREATE TABLE IF NOT EXISTS sick_step_up_decision (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL,
    note_id     UUID        NOT NULL,
    accepted    BOOLEAN     NOT NULL,
    decided_at  TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_sick_step_up_decision_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_sick_step_up_decision_user_note UNIQUE (user_id, note_id)
);

COMMENT ON TABLE sick_segment IS
    'Illness segments: a severity change closes the open segment and opens a new one in the same episode.';
COMMENT ON TABLE sick_step_up_decision IS
    'User accept/decline of a severity step-up proposal for a given correction note; one decision per (user, note).';

-- AI assistant chat history: one row per user or assistant turn.
CREATE TABLE IF NOT EXISTS chat_message (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    role        VARCHAR(16)  NOT NULL,
    text        TEXT,
    photo_key   VARCHAR(500),
    intent      VARCHAR(32),
    payload     JSONB,
    note_ids    JSONB,
    model_id    VARCHAR(128),
    latency_ms  BIGINT,
    CONSTRAINT fk_chat_message_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_chat_message_role CHECK (role IN ('USER', 'ASSISTANT'))
);

CREATE INDEX IF NOT EXISTS idx_chat_message_user_created ON chat_message(user_id, created_at DESC);

COMMENT ON TABLE chat_message IS
    'AI assistant chat turns. payload holds the structured assistant reply; note_ids the notes it created.';

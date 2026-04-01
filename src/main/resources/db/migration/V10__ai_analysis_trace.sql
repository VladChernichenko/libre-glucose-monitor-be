CREATE TABLE IF NOT EXISTS ai_analysis_trace (
    id                 UUID PRIMARY KEY,
    user_id            UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    model_id           VARCHAR(128) NOT NULL,
    window_hours       INTEGER NOT NULL,
    context_hash       VARCHAR(128) NOT NULL,
    evidence_chunk_ids TEXT,
    confidence         DOUBLE PRECISION NOT NULL,
    latency_ms         BIGINT NOT NULL,
    created_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ai_analysis_trace_user_created
    ON ai_analysis_trace (user_id, created_at DESC);

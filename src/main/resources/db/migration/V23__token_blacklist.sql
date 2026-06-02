-- Durable JWT revocation (BE-H3). Replaces the in-memory blacklist so that logout /
-- logout-all survive restarts and work across multiple backend instances.
-- We store a SHA-256 hex hash of the token (or logout-all sentinel), never the raw token.
CREATE TABLE token_blacklist (
    token_hash VARCHAR(64) PRIMARY KEY,
    expires_at TIMESTAMP NOT NULL
);

-- Supports the periodic cleanup of naturally-expired entries.
CREATE INDEX idx_token_blacklist_expires_at ON token_blacklist (expires_at);
